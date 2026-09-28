#!/usr/bin/env python3
"""One-shot data migration: PostgreSQL (research-agent) -> MongoDB (research-agent).

Reads `research_session` + `research_step` from PostgreSQL READ-ONLY and writes one
MongoDB document per session into collection `research_session`, with the steps
EMBEDDED as an array sorted by orderIndex — matching the Spring Data Mongo entities
(ResearchSession / ResearchStep) exactly:

  research_session.id            -> _id            (BSON UUID, STANDARD representation)
  topic, status, prompt          -> same names
  final_report                   -> finalReport
  created_at / updated_at /      -> createdAt / updatedAt / completedAt
    completed_at                     (BSON date, normalized to UTC)

  research_step.id               -> steps[].id     (BSON UUID, STANDARD representation)
  order_index                    -> steps[].orderIndex
  type, content, status          -> same names
  created_at                     -> steps[].createdAt

The script is idempotent: documents are upserted by _id, so re-running after an
interruption converges to the same state. It NEVER writes to PostgreSQL.

IMPORTANT: UUIDs must be stored with the STANDARD (RFC 4122) BSON representation —
this matches `spring.mongodb.representation.uuid: STANDARD` in application.yml.
If you change one, change both or lookups will silently fail.

Usage:
  python migrate_pg_to_mongo.py --dry-run          # report what would be migrated
  python migrate_pg_to_mongo.py                    # run the migration + verify
  python migrate_pg_to_mongo.py --skip-existing    # never touch docs already in Mongo

Requires: psycopg2-binary, pymongo (see requirements.txt next to this script).
"""

import argparse
import sys
import uuid
from datetime import timezone

import psycopg2
import psycopg2.extras
from bson import Binary
from bson.binary import UUID_SUBTYPE, UuidRepresentation  # UUID_SUBTYPE=4 is the standard (RFC 4122) representation
from pymongo import MongoClient


def to_utc(dt):
    """PG timestamptz -> naive UTC datetime (pymongo stores naive datetimes as UTC)."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        return dt
    return dt.astimezone(timezone.utc).replace(tzinfo=None)


def to_bson_uuid(u):
    """PG uuid (str or UUID) -> BSON binary with the STANDARD (RFC 4122) representation."""
    if isinstance(u, str):
        u = uuid.UUID(u)
    return Binary(u.bytes, UUID_SUBTYPE)


def fetch_from_postgres(args):
    conn = psycopg2.connect(
        host=args.pg_host, port=args.pg_port, dbname=args.pg_db,
        user=args.pg_user, password=args.pg_password,
    )
    try:
        conn.set_session(readonly=True, autocommit=True)  # hard guarantee: PG is read-only here
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

        cur.execute("SELECT id, topic, status, prompt, final_report, created_at, updated_at, completed_at "
                    "FROM research_session")
        sessions = cur.fetchall()

        cur.execute("SELECT id, session_id, order_index, type, content, status, created_at "
                    "FROM research_step ORDER BY session_id, order_index")
        steps_by_session = {}
        for row in cur.fetchall():
            steps_by_session.setdefault(row["session_id"], []).append(row)
    finally:
        conn.close()
    return sessions, steps_by_session


def build_documents(sessions, steps_by_session):
    docs = []
    for s in sessions:
        steps = [
            {
                "id": to_bson_uuid(st["id"]),
                "orderIndex": st["order_index"],
                "type": st["type"],
                **({"content": st["content"]} if st["content"] is not None else {}),
                "status": st["status"],
                "createdAt": to_utc(st["created_at"]),
            }
            for st in steps_by_session.get(s["id"], [])
        ]
        doc = {
            "_id": to_bson_uuid(s["id"]),
            "topic": s["topic"],
            "status": s["status"],
            **({"prompt": s["prompt"]} if s["prompt"] is not None else {}),
            "steps": steps,
            **({"finalReport": s["final_report"]} if s["final_report"] is not None else {}),
            "createdAt": to_utc(s["created_at"]),
            "updatedAt": to_utc(s["updated_at"]),
            **({"completedAt": to_utc(s["completed_at"])} if s["completed_at"] is not None else {}),
        }
        docs.append(doc)
    return docs


def verify(mongo_db, args):
    coll = mongo_db["research_session"]
    pg_sessions_count = args._pg_sessions_count  # set by run()
    pg_steps_count = args._pg_steps_count

    mongo_sessions = coll.count_documents({})
    mongo_steps = sum(d.get("n", 0) for d in coll.aggregate(
        [{"$group": {"_id": None, "n": {"$sum": {"$size": "$steps"}}}}]))

    print("\n=== Verification ===")
    print(f"PostgreSQL: {pg_sessions_count} sessions, {pg_steps_count} steps (source of truth)")
    print(f"MongoDB:    {mongo_sessions} documents, {mongo_steps} embedded steps")

    ok = mongo_sessions == pg_sessions_count and mongo_steps == pg_steps_count

    # Spot-check: every session's _id must round-trip as a UUID the app can use.
    sample = coll.find_one({"createdAt": {"$exists": True}},
                           {"_id": 1, "topic": 1, "status": 1, "steps.orderIndex": 1})
    if sample:
        sid = sample["_id"].as_uuid(UuidRepresentation.STANDARD)
        print(f"Spot check: _id {sid} | topic={sample['topic']!r} | status={sample['status']}")

    indexes = {i["name"]: i["key"] for i in coll.list_indexes()}
    expected = {"createdAt_1": [("createdAt", 1)], "status_createdAt": [("status", 1), ("createdAt", -1)]}
    for name, key in expected.items():
        present = indexes.get(name) == key
        print(f"Index {name}: {'OK' if present else 'MISSING (created automatically on next app start)'}")

    return ok


def run(args):
    sessions, steps_by_session = fetch_from_postgres(args)
    args._pg_sessions_count = len(sessions)
    args._pg_steps_count = sum(len(v) for v in steps_by_session.values())
    docs = build_documents(sessions, steps_by_session)

    print(f"Read {len(docs)} sessions ({args._pg_steps_count} steps total) from PostgreSQL "
          f"{args.pg_host}:{args.pg_port}/{args.pg_db}")

    if args.dry_run:
        with_steps = sum(1 for d in docs if d["steps"])
        print(f"DRY RUN: would upsert {len(docs)} documents ({with_steps} with steps) "
              f"into {args.mongo_uri} -> research_session. Nothing written.")
        return 0

    client = MongoClient(args.mongo_uri)
    mongo_db = client[args.mongo_db]
    coll = mongo_db["research_session"]

    existing = set()
    if args.skip_existing:
        for row in coll.find({}, {"_id": 1}):
            existing.add(row["_id"].bytes)

    upserted = skipped = 0
    for doc in docs:
        if doc["_id"] in existing:
            skipped += 1
            continue
        coll.replace_one({"_id": doc["_id"]}, doc, upsert=True)
        upserted += 1

    print(f"Wrote {upserted} documents" + (f", skipped {skipped} existing" if args.skip_existing else "")
          + f" into {args.mongo_uri} -> research_session")

    ok = verify(mongo_db, args)
    if not ok:
        print("MIGRATION VERIFICATION FAILED — do NOT cut over. Compare the counts above.")
        return 1
    print("Migration verified OK. PostgreSQL was not modified (session opened read-only).")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--pg-host", default="localhost")
    p.add_argument("--pg-port", type=int, default=5432)
    p.add_argument("--pg-db", default="research-agent")
    p.add_argument("--pg-user", default="postgres")
    p.add_argument("--pg-password", default="postgres")
    p.add_argument("--mongo-uri", default="mongodb://localhost:27017")
    p.add_argument("--mongo-db", default="research-agent")
    p.add_argument("--dry-run", action="store_true", help="report what would be migrated, write nothing")
    p.add_argument("--skip-existing", action="store_true",
                   help="never overwrite documents already present in Mongo (use after cutover)")
    args = p.parse_args()
    sys.exit(run(args))


if __name__ == "__main__":
    main()
