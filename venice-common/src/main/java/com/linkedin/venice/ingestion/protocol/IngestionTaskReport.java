/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.linkedin.venice.ingestion.protocol;

@SuppressWarnings("all")
public class IngestionTaskReport extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = org.apache.avro.Schema.parse("{\"type\":\"record\",\"name\":\"IngestionTaskReport\",\"namespace\":\"com.linkedin.venice.ingestion.protocol\",\"fields\":[{\"name\":\"topicName\",\"type\":\"string\",\"default\":\"\"},{\"name\":\"partitionId\",\"type\":\"int\"},{\"name\":\"offset\",\"type\":\"long\"},{\"name\":\"isEndOfPushReceived\",\"type\":\"boolean\",\"default\":false},{\"name\":\"isComplete\",\"type\":\"boolean\",\"default\":false},{\"name\":\"isError\",\"type\":\"boolean\",\"default\":false},{\"name\":\"isPositive\",\"type\":\"boolean\",\"doc\":\"A true/false flag to respond whether a partition is consuming\",\"default\":false},{\"name\":\"errorMessage\",\"type\":\"string\",\"default\":\"\"},{\"name\":\"offsetRecord\",\"type\":[\"null\",\"bytes\"],\"default\":null},{\"name\":\"storeVersionState\",\"type\":[\"null\",\"bytes\"],\"default\":null}]}");
  public java.lang.CharSequence topicName;
  public int partitionId;
  public long offset;
  public boolean isEndOfPushReceived;
  public boolean isComplete;
  public boolean isError;
  /** A true/false flag to respond whether a partition is consuming */
  public boolean isPositive;
  public java.lang.CharSequence errorMessage;
  public java.nio.ByteBuffer offsetRecord;
  public java.nio.ByteBuffer storeVersionState;
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return topicName;
    case 1: return partitionId;
    case 2: return offset;
    case 3: return isEndOfPushReceived;
    case 4: return isComplete;
    case 5: return isError;
    case 6: return isPositive;
    case 7: return errorMessage;
    case 8: return offsetRecord;
    case 9: return storeVersionState;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: topicName = (java.lang.CharSequence)value$; break;
    case 1: partitionId = (java.lang.Integer)value$; break;
    case 2: offset = (java.lang.Long)value$; break;
    case 3: isEndOfPushReceived = (java.lang.Boolean)value$; break;
    case 4: isComplete = (java.lang.Boolean)value$; break;
    case 5: isError = (java.lang.Boolean)value$; break;
    case 6: isPositive = (java.lang.Boolean)value$; break;
    case 7: errorMessage = (java.lang.CharSequence)value$; break;
    case 8: offsetRecord = (java.nio.ByteBuffer)value$; break;
    case 9: storeVersionState = (java.nio.ByteBuffer)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
}