package bwj.codesage.repository;

import bwj.codesage.domain.entity.CodeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {
}
