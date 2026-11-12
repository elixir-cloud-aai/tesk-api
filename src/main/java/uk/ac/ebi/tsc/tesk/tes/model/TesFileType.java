package uk.ac.ebi.tsc.tesk.tes.model;

import com.fasterxml.jackson.annotation.JsonValue;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Gets or Sets tesFileType
 */
public enum TesFileType {
  
  FILE("FILE"),
  
  DIRECTORY("DIRECTORY");

  private String value;

  TesFileType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }

  @JsonCreator
  public static TesFileType fromValue(String value) {
    for (TesFileType b : TesFileType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    //throw new IllegalArgumentException("Unexpected value '" + value + "'");
    //Backwards compatibility: need to return null for illegal state
    return null;
  }

  /**
   * Reads the type of an input or output from JSON. TES 1.1 doesn't require it (issue #146): an
   * explicit {@code null} is {@code FILE}, as a left-out type is. Unknown values are {@code null},
   * as with {@link #fromValue}, and fail validation. The models use a String setter for this,
   * because Jackson sends nulls returned by an enum deserializer through the same null handling as
   * JSON {@code null}.
   *
   * @param value the type as sent, or {@code null}
   * @return the type, or {@code null} for unknown values
   */
  public static TesFileType fromJson(String value) {
    return value == null ? FILE : fromValue(value);
  }
}

