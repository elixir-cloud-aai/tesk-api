package uk.ac.ebi.tsc.tesk.tes.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;
import javax.validation.Valid;
import javax.validation.constraints.*;

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
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

