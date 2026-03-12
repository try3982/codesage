package bwj.codesage.domain.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FloatArrayConverter implements AttributeConverter<float[], String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) return null;
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert float[] to JSON", e);
        }
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            float[] result = objectMapper.readValue(dbData, float[].class);
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert JSON to float[]", e);
        }
    }
}
