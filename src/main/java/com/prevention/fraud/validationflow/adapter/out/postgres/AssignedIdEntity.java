package com.prevention.fraud.validationflow.adapter.out.postgres;

import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

/** Id assigned by the application: {@link Persistable} makes Spring Data INSERT without a prior SELECT. */
@MappedSuperclass
abstract class AssignedIdEntity implements Persistable<UUID> {

	@Id
	UUID id;

	@Transient
	private boolean isNew = true;

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		isNew = false;
	}

}
