package com.researchagent.repository;

import com.researchagent.model.entity.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends MongoRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
