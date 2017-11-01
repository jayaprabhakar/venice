/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.linkedin.venice.controller.kafka.protocol.admin;

@SuppressWarnings("all")
public class UpdateStore extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = org.apache.avro.Schema.parse("{\"type\":\"record\",\"name\":\"UpdateStore\",\"namespace\":\"com.linkedin.venice.controller.kafka.protocol.admin\",\"fields\":[{\"name\":\"clusterName\",\"type\":\"string\"},{\"name\":\"storeName\",\"type\":\"string\"},{\"name\":\"owner\",\"type\":\"string\"},{\"name\":\"partitionNum\",\"type\":\"int\"},{\"name\":\"currentVersion\",\"type\":\"int\"},{\"name\":\"enableReads\",\"type\":\"boolean\"},{\"name\":\"enableWrites\",\"type\":\"boolean\"},{\"name\":\"storageQuotaInByte\",\"type\":\"long\"},{\"name\":\"readQuotaInCU\",\"type\":\"long\"},{\"name\":\"hybridStoreConfig\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"HybridStoreConfigRecord\",\"fields\":[{\"name\":\"rewindTimeInSeconds\",\"type\":\"long\"},{\"name\":\"offsetLagThresholdToGoOnline\",\"type\":\"long\"}]}],\"default\":null},{\"name\":\"accessControlled\",\"type\":\"boolean\"},{\"name\":\"compressionStrategy\",\"type\":\"int\",\"doc\":\"Using int because Avro Enums are not evolvable\"}]}");
  public java.lang.CharSequence clusterName;
  public java.lang.CharSequence storeName;
  public java.lang.CharSequence owner;
  public int partitionNum;
  public int currentVersion;
  public boolean enableReads;
  public boolean enableWrites;
  public long storageQuotaInByte;
  public long readQuotaInCU;
  public com.linkedin.venice.controller.kafka.protocol.admin.HybridStoreConfigRecord hybridStoreConfig;
  public boolean accessControlled;
  /** Using int because Avro Enums are not evolvable */
  public int compressionStrategy;
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return clusterName;
    case 1: return storeName;
    case 2: return owner;
    case 3: return partitionNum;
    case 4: return currentVersion;
    case 5: return enableReads;
    case 6: return enableWrites;
    case 7: return storageQuotaInByte;
    case 8: return readQuotaInCU;
    case 9: return hybridStoreConfig;
    case 10: return accessControlled;
    case 11: return compressionStrategy;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: clusterName = (java.lang.CharSequence)value$; break;
    case 1: storeName = (java.lang.CharSequence)value$; break;
    case 2: owner = (java.lang.CharSequence)value$; break;
    case 3: partitionNum = (java.lang.Integer)value$; break;
    case 4: currentVersion = (java.lang.Integer)value$; break;
    case 5: enableReads = (java.lang.Boolean)value$; break;
    case 6: enableWrites = (java.lang.Boolean)value$; break;
    case 7: storageQuotaInByte = (java.lang.Long)value$; break;
    case 8: readQuotaInCU = (java.lang.Long)value$; break;
    case 9: hybridStoreConfig = (com.linkedin.venice.controller.kafka.protocol.admin.HybridStoreConfigRecord)value$; break;
    case 10: accessControlled = (java.lang.Boolean)value$; break;
    case 11: compressionStrategy = (java.lang.Integer)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
}
