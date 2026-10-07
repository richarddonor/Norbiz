package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PullOutReceive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PullOutReceiveRepository extends JpaRepository<PullOutReceive, Long>, JpaSpecificationExecutor<PullOutReceive> {
}
