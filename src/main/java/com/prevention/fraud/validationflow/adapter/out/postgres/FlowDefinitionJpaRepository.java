package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.prevention.fraud.validationflow.domain.flow.FlowStatus;
import com.prevention.fraud.validationflow.domain.flow.InputField;

/** Every query is scoped by tenantId. */
interface FlowDefinitionJpaRepository extends JpaRepository<FlowDefinitionEntity, UUID> {

	@Query("select coalesce(max(f.version), 0) + 1 from FlowDefinitionEntity f "
			+ "where f.tenantId = :tenantId and f.flowKey = :flowKey")
	int nextVersion(@Param("tenantId") String tenantId, @Param("flowKey") String flowKey);

	Optional<FlowDefinitionEntity> findByTenantIdAndId(String tenantId, UUID id);

	List<FlowDefinitionEntity> findTop2ByTenantIdAndStatusAndFlowKey(String tenantId, FlowStatus status,
			String flowKey);

	List<FlowDefinitionEntity> findTop2ByTenantIdAndStatusAndUserTypeAndContext(String tenantId, FlowStatus status,
			String userType, String context);

	@Query("""
			select f from FlowDefinitionEntity f where f.tenantId = :tenantId
			  and (:flowKey is null or f.flowKey = :flowKey) and (:status is null or f.status = :status)
			  and (:userType is null or f.userType = :userType) and (:context is null or f.context = :context)""")
	Page<FlowDefinitionEntity> search(@Param("tenantId") String tenantId, @Param("flowKey") String flowKey,
			@Param("status") FlowStatus status, @Param("userType") String userType,
			@Param("context") String context, Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update FlowDefinitionEntity f set f.userType = :userType, f.context = :context,
			  f.displayName = :displayName, f.description = :description, f.graphDefinition = :graph,
			  f.inputContract = :contract, f.metadata = :metadata, f.updatedAt = :now
			where f.tenantId = :tenantId and f.id = :id and f.status = 'DRAFT'""")
	int updateDraft(@Param("tenantId") String tenantId, @Param("id") UUID id, @Param("userType") String userType,
			@Param("context") String context, @Param("displayName") String displayName,
			@Param("description") String description, @Param("graph") Map<String, Object> graph,
			@Param("contract") List<InputField> contract, @Param("metadata") Map<String, Object> metadata,
			@Param("now") Instant now);

	/** Archives the tenant's ACTIVE version of the same (userType, context) as flow {@code id}. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update FlowDefinitionEntity f set f.status = 'ARCHIVED', f.updatedAt = :now
			where f.tenantId = :tenantId and f.status = 'ACTIVE' and exists (
			  select 1 from FlowDefinitionEntity x where x.tenantId = :tenantId and x.id = :id
			    and x.userType = f.userType and x.context = f.context)""")
	int archiveActiveOfSelector(@Param("tenantId") String tenantId, @Param("id") UUID id, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update FlowDefinitionEntity f set f.status = 'ACTIVE', f.updatedAt = :now "
			+ "where f.tenantId = :tenantId and f.id = :id and f.status = 'DRAFT'")
	int activateDraft(@Param("tenantId") String tenantId, @Param("id") UUID id, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update FlowDefinitionEntity f set f.status = 'ARCHIVED', f.updatedAt = :now "
			+ "where f.tenantId = :tenantId and f.id = :id and f.status <> 'ARCHIVED'")
	int archive(@Param("tenantId") String tenantId, @Param("id") UUID id, @Param("now") Instant now);

}
