package bwj.codesage.domain.entity;

import bwj.codesage.domain.converter.FloatArrayConverter;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "code_chunks")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private AnalysisJob job;

    private String filePath;

    private String language;

    @Column(columnDefinition = "TEXT")
    private String content;

    private int chunkIndex;

    @Convert(converter = FloatArrayConverter.class)
    @Column(columnDefinition = "TEXT")
    private float[] embedding;
}
