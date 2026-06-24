package com.paymentengine.repository;

import com.paymentengine.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Fetches unpublished events in insertion order — processed by OutboxPublisherScheduler
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.published = true, o.publishedAt = :now WHERE o.id = :id")
    void markAsPublished(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
