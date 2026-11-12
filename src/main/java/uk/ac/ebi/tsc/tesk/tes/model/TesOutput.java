package uk.ac.ebi.tsc.tesk.tes.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import javax.validation.Valid;
import javax.validation.constraints.*;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ABSOLUTE_PATH_MESSAGE;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ABSOLUTE_PATH_REGEXP;

/**
 * Output describes Task output files.
 */
@ApiModel(description = "Output describes Task output files.")
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2021-03-24T17:10:08.716Z[Europe/London]")
public class TesOutput   {
  @JsonProperty("name")
  private String name;

  @JsonProperty("description")
  private String description;

  @JsonProperty("url")
  private String url;

  @JsonProperty("path")
  private String path;

  @JsonProperty("path_prefix")
  private String pathPrefix;

  @JsonProperty("type")
  private TesFileType type = TesFileType.FILE;

  public TesOutput name(String name) {
    this.name = name;
    return this;
  }

  /**
   * User-provided name of output file
   * @return name
  */
  @ApiModelProperty(value = "User-provided name of output file")


  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public TesOutput description(String description) {
    this.description = description;
    return this;
  }

  /**
   * Optional users provided description field, can be used for documentation.
   * @return description
  */
  @ApiModelProperty(value = "Optional users provided description field, can be used for documentation.")


  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public TesOutput url(String url) {
    this.url = url;
    return this;
  }

  /**
   * URL for the file to be copied by the TES server after the task is complete. For Example:  - `s3://my-object-store/file1`  - `gs://my-bucket/file2`  - `file:///path/to/my/file`
   * @return url
  */
  @ApiModelProperty(required = true, value = "URL for the file to be copied by the TES server after the task is complete. For Example:  - `s3://my-object-store/file1`  - `gs://my-bucket/file2`  - `file:///path/to/my/file`")
  @NotNull
  @NotBlank

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public TesOutput path(String path) {
    this.path = path;
    return this;
  }

  /**
   * Path of the file inside the container. Must be an absolute path.
   * @return path
  */
  @ApiModelProperty(example = "/data/file1", required = true, value = "Path of the file inside the container. Must be an absolute path.")
  @NotNull
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)
  @NotBlank
  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public TesOutput pathPrefix(String pathPrefix) {
    this.pathPrefix = pathPrefix;
    return this;
  }

  /**
   * Prefix to be removed from matching outputs if `path` contains wildcards (TES 1.1); output URLs
   * are constructed by appending pruned paths to the directory specified in `url`. Without it, a
   * `path` with wildcards is taken as before: expanded for `file:` URLs, a file name otherwise.
   * @return pathPrefix
  */
  @ApiModelProperty(example = "/data", value = "Prefix to be removed from matching outputs if `path` contains wildcards; output URLs are constructed by appending pruned paths to the directory specified in `url`. Ignored if `path` has no wildcards.")
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)
  public String getPathPrefix() {
    return pathPrefix;
  }

  public void setPathPrefix(String pathPrefix) {
    this.pathPrefix = pathPrefix;
  }

  /**
   * An output with wildcards is mounted at its deepest directory without wildcards, which a
   * pattern like `/*.txt` doesn't have.
   *
   * @return whether `path` has no wildcards in its first component, if it is used as a pattern
   */
  @JsonIgnore
  @AssertTrue(message = "path with wildcards must have none in its first component, e.g. /data/*.txt rather than /*.txt")
  public boolean isPatternBelowRoot() {
    return !isPattern() || !patternRoot(path).isEmpty();
  }

  /**
   * The filer removes `path_prefix` from each match, so it must be the pattern's directory
   * without wildcards, or above it; else the output fails after the executors ran.
   *
   * @return whether `path_prefix` is a component-wise prefix of the pattern's directory
   */
  @JsonIgnore
  @AssertTrue(message = "path_prefix must be the part of path before its first wildcard, or a directory above it")
  public boolean isPathPrefixAbovePattern() {
    if (!isPattern() || patternRoot(path).isEmpty()) {
      return true;
    }
    String prefix = normalize(pathPrefix);
    String root = patternRoot(path);
    return prefix.equals("/") || root.equals(prefix) || root.startsWith(prefix + "/");
  }

  private boolean isPattern() {
    return path != null && pathPrefix != null && !pathPrefix.isEmpty() && path.matches(".*[*?\\[].*");
  }

  /** The directories of a pattern before its first component with wildcards, normalized. */
  static String patternRoot(String pattern) {
    StringBuilder root = new StringBuilder();
    for (String part : normalize(pattern).split("/")) {
      if (part.isEmpty()) {
        continue;
      }
      if (part.matches(".*[*?\\[].*")) {
        break;
      }
      root.append('/').append(part);
    }
    return root.toString();
  }

  /** Resolves `.`, `..` (up to the root) and repeated slashes of an absolute path. */
  static String normalize(String path) {
    java.util.Deque<String> parts = new java.util.ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        parts.pollLast();
      } else {
        parts.addLast(part);
      }
    }
    return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
  }

  public TesOutput type(TesFileType type) {
    this.type = type;
    return this;
  }

  /**
   * Get type
   * @return type
  */
  @ApiModelProperty(
      value = "FILE if left out or null; directories need DIRECTORY",
      allowableValues = "DIRECTORY,FILE")
  @NotNull

  @Valid

  public TesFileType getType() {
    return type;
  }

  public void setType(TesFileType type) {
    this.type = type;
  }

  /**
   * Reads the type from JSON. TES 1.1 doesn't require it (issue #146): left out, it keeps its
   * default, and an explicit {@code null} is {@code FILE} too. Unknown values become {@code null}
   * and fail validation.
   */
  @JsonSetter("type")
  private void setTypeFromJson(String type) {
    this.type = type == null ? TesFileType.FILE : TesFileType.fromValue(type);
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TesOutput tesOutput = (TesOutput) o;
    return Objects.equals(this.name, tesOutput.name) &&
        Objects.equals(this.description, tesOutput.description) &&
        Objects.equals(this.url, tesOutput.url) &&
        Objects.equals(this.path, tesOutput.path) &&
        Objects.equals(this.pathPrefix, tesOutput.pathPrefix) &&
        Objects.equals(this.type, tesOutput.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, description, url, path, pathPrefix, type);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TesOutput {\n");
    
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    url: ").append(toIndentedString(url)).append("\n");
    sb.append("    path: ").append(toIndentedString(path)).append("\n");
    sb.append("    pathPrefix: ").append(toIndentedString(pathPrefix)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

