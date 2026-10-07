package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Assembly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AssemblyRepository extends JpaRepository<Assembly, Long>, JpaSpecificationExecutor<Assembly> {
}
