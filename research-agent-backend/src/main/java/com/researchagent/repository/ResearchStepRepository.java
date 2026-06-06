package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.entity.ResearchStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchStepRepository extends JpaRepository<ResearchStep, UUID> {

    List<ResearchStep> findBySessionOrderByOrderIndexAsc(ResearchSession session);

    void deleteBySession(ResearchSession session);
}
