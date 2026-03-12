package bwj.codesage.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CodeChunkingService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".go", ".rb", ".rs",
            ".cpp", ".c", ".cs", ".php", ".swift", ".scala",
            ".xml", ".yml", ".yaml", ".json", ".gradle", ".properties", ".md"
    );

    private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
            Map.entry(".java", "Java"),
            Map.entry(".kt", "Kotlin"),
            Map.entry(".py", "Python"),
            Map.entry(".js", "JavaScript"),
            Map.entry(".ts", "TypeScript"),
            Map.entry(".go", "Go"),
            Map.entry(".rb", "Ruby"),
            Map.entry(".rs", "Rust"),
            Map.entry(".cpp", "C++"),
            Map.entry(".c", "C"),
            Map.entry(".cs", "C#"),
            Map.entry(".php", "PHP"),
            Map.entry(".swift", "Swift"),
            Map.entry(".scala", "Scala"),
            Map.entry(".xml", "XML"),
            Map.entry(".yml", "YAML"),
            Map.entry(".yaml", "YAML"),
            Map.entry(".json", "JSON"),
            Map.entry(".gradle", "Gradle"),
            Map.entry(".properties", "Properties"),
            Map.entry(".md", "Markdown")
    );

    private static final int CHUNK_SIZE = 3000;

    public boolean isSupportedFile(String filePath) {
        if (filePath == null) return false;
        String ext = getExtension(filePath);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    public String detectLanguage(String filePath) {
        if (filePath == null) return "unknown";
        String ext = getExtension(filePath);
        return LANGUAGE_MAP.getOrDefault(ext, "unknown");
    }

    public List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) return chunks;

        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());
            String chunk = content.substring(start, end);
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end;
        }
        return chunks;
    }

    /**
     * 파일 우선순위 반환 (낮을수록 먼저 처리)
     * Controller > Service > Repository > Entity > Config > 나머지
     */
    public int getFilePriority(String filePath) {
        if (filePath == null) return 99;
        String name = filePath.toLowerCase();
        if (name.contains("controller")) return 0;
        if (name.contains("service"))    return 1;
        if (name.contains("repository") || name.contains("repo")) return 2;
        if (name.contains("entity") || name.contains("domain/")) return 3;
        if (name.contains("config"))     return 4;
        return 5;
    }

    private String getExtension(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex == -1) return "";
        return filePath.substring(dotIndex).toLowerCase();
    }
}
