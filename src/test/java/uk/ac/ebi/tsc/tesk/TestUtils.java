package uk.ac.ebi.tsc.tesk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

public class TestUtils {
    private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

    public static String getFileContentFromResources(String path) throws IOException {
        Resource fileFromResources = new ClassPathResource(path);
        return new String(Files.readAllBytes(fileFromResources.getFile().toPath()));
    }

    /**
     * The taskmaster's input without the empty arrays and objects in its executors' jobs: the
     * Kubernetes client writes the lists and maps of its models also if they are empty. Lossy: an
     * empty field that TESK API sets on purpose is gone too, so tests check those separately. The
     * rest of the input (e.g. its {@code inputs}, {@code outputs} and {@code volumes}) is kept.
     */
    public static String withoutEmptyInExecutors(String json) {
        JsonObject input = JsonParser.parseString(json).getAsJsonObject();
        JsonElement executors = input.get("executors");
        if (executors != null && executors.isJsonArray()) {
            executors.getAsJsonArray().forEach(TestUtils::removeEmpty);
        }
        return input.toString();
    }

    /**
     * Removes the members of objects that are empty arrays or objects, or become empty without
     * their own empty members, at any depth, and tells whether the element is (then) empty.
     * Elements of arrays are cleaned, but kept even if they become empty.
     */
    private static boolean removeEmpty(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet().toArray(new String[0])) {
                if (removeEmpty(object.get(key))) {
                    object.remove(key);
                }
            }
            return object.size() == 0;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            array.forEach(TestUtils::removeEmpty);
            return array.size() == 0;
        }
        return false;
    }
}
