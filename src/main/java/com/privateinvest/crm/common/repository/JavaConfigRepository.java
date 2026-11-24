package com.privateinvest.crm.common.repository;

import com.privateinvest.crm.common.entity.JavaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JavaConfigRepository extends JpaRepository<JavaConfig, String> {
    Optional<JavaConfig> findByKey(String key);
}
