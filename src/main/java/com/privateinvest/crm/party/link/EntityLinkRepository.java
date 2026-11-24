package com.privateinvest.crm.party.link;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EntityLinkRepository extends JpaRepository<EntityLink, UUID> {

    List<EntityLink> findByPersonId(UUID personId);

    boolean existsByPersonIdAndEntityId(UUID personId, UUID entityId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EntityLink l set l.primary = false where l.personId = :personId and l.primary = true")
    void clearPrimary(UUID personId);
}
