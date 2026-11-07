package uk.ac.ebi.tsc.tesk.tes.model;

import java.math.BigDecimal;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.Digits;

/**
 * Resources describes the resources requested by a task.
 */
@ApiModel(description = "Resources describes the resources requested by a task.", value="tesResources")
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2021-03-24T17:10:08.716Z[Europe/London]")
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class TesResources   {
  @JsonProperty("cpu_cores")
  private BigDecimal cpuCores;

  @JsonProperty("preemptible")
  private Boolean preemptible;

  @JsonProperty("ram_gb")
  private Double ramGb;

  @JsonProperty("disk_gb")
  private Double diskGb;

  @JsonProperty("zones")
  @Valid
  private List<String> zones = null;

  @JsonProperty("backend_parameters")
  private Map<String, String> backendParameters = null;

  @JsonProperty("backend_parameters_strict")
  private Boolean backendParametersStrict = false;

  public TesResources cpuCores(BigDecimal cpuCores) {
    setCpuCores(cpuCores);
    return this;
  }

  /**
   * Requested number of CPUs. The TES specification defines an integer; TESK also accepts decimal numbers
   * (e.g. 0.5), which it requests from Kubernetes as such, and writes the number back as given
   * @return cpuCores
  */
  @ApiModelProperty(example = "4", value = "Requested number of CPUs (TESK also accepts decimal numbers, e.g. 0.5)")
  //at most 999999.999: Kubernetes counts CPU in steps of 1m; bounds the number before it is converted
  @Digits(integer = 6, fraction = 3)

  public BigDecimal getCpuCores() {
    return cpuCores;
  }

  public void setCpuCores(BigDecimal cpuCores) {
    //whole numbers given with an exponent (e.g. 1e3) are kept without it, so that they are written as integers
    this.cpuCores = cpuCores != null && cpuCores.scale() < 0 && cpuCores.precision() - cpuCores.scale() <= 7
        ? cpuCores.setScale(0) : cpuCores;
  }

  public TesResources preemptible(Boolean preemptible) {
    this.preemptible = preemptible;
    return this;
  }

  /**
   * Define if the task is allowed to run on preemptible compute instances, for example, AWS Spot. This option may have no effect when utilized on some backends that don't have the concept of preemptible jobs.
   * @return preemptible
  */
  @ApiModelProperty(example = "false", value = "Define if the task is allowed to run on preemptible compute instances, for example, AWS Spot. This option may have no effect when utilized on some backends that don't have the concept of preemptible jobs.")


  public Boolean getPreemptible() {
    return preemptible;
  }

  public void setPreemptible(Boolean preemptible) {
    this.preemptible = preemptible;
  }

  public TesResources ramGb(Double ramGb) {
    this.ramGb = ramGb;
    return this;
  }

  /**
   * Requested RAM required in gigabytes (GB)
   * @return ramGb
  */
  @ApiModelProperty(example = "8", value = "Requested RAM required in gigabytes (GB)")


  public Double getRamGb() {
    return ramGb;
  }

  public void setRamGb(Double ramGb) {
    this.ramGb = ramGb;
  }

  public TesResources diskGb(Double diskGb) {
    this.diskGb = diskGb;
    return this;
  }

  /**
   * Requested disk size in gigabytes (GB)
   * @return diskGb
  */
  @ApiModelProperty(example = "40", value = "Requested disk size in gigabytes (GB)")


  public Double getDiskGb() {
    return diskGb;
  }

  public void setDiskGb(Double diskGb) {
    this.diskGb = diskGb;
  }

  public TesResources zones(List<String> zones) {
    this.zones = zones;
    return this;
  }

  public TesResources addZonesItem(String zonesItem) {
    if (this.zones == null) {
      this.zones = new ArrayList<>();
    }
    this.zones.add(zonesItem);
    return this;
  }

  /**
   * Request that the task be run in these compute zones. How this string is utilized will be dependent on the backend system. For example, a system based on a cluster queueing system may use this string to define priorty queue to which the job is assigned.
   * @return zones
  */
  @ApiModelProperty(example = "[\"us-west-1\"]", value = "Request that the task be run in these compute zones. How this string is utilized will be dependent on the backend system. For example, a system based on a cluster queueing system may use this string to define priorty queue to which the job is assigned.")


  public List<String> getZones() {
    return zones;
  }

  public void setZones(List<String> zones) {
    this.zones = zones;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TesResources tesResources = (TesResources) o;
    return Objects.equals(this.cpuCores, tesResources.cpuCores) &&
        Objects.equals(this.preemptible, tesResources.preemptible) &&
        Objects.equals(this.ramGb, tesResources.ramGb) &&
        Objects.equals(this.diskGb, tesResources.diskGb) &&
        Objects.equals(this.zones, tesResources.zones);
  }

  @Override
  public int hashCode() {
    return Objects.hash(cpuCores, preemptible, ramGb, diskGb, zones);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TesResources {\n");
    
    sb.append("    cpuCores: ").append(toIndentedString(cpuCores)).append("\n");
    sb.append("    preemptible: ").append(toIndentedString(preemptible)).append("\n");
    sb.append("    ramGb: ").append(toIndentedString(ramGb)).append("\n");
    sb.append("    diskGb: ").append(toIndentedString(diskGb)).append("\n");
    sb.append("    zones: ").append(toIndentedString(zones)).append("\n");
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

