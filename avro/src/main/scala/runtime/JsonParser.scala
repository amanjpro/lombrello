package com.googlecode.avro
package runtime

import org.apache.avro.generic.{GenericData, IndexedRecord}
import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.util.Utf8

import org.codehaus.jackson.{JsonFactory, JsonParser, JsonToken}

import scala.collection.JavaConversions._

import org.apache.log4j.Logger

object JsonObject {
  val factory = new JsonFactory
}

class JsonObject(json: String) {
  val logger = Logger.getLogger("scads.avro.jsonparser")

  def toAvro[T <: IndexedRecord](implicit manifest: Manifest[T]): Option[T] = {
    val parser = JsonObject.factory.createJsonParser(json)
    val record = manifest.runtimeClass.newInstance().asInstanceOf[T]

    logger.debug("Parsing: " + json)

    if(parser.nextToken != JsonToken.START_OBJECT) {
      logger.warn("Failed to parse JSON object: " + json + ". Expected START_OBJECT, found " + parser.getCurrentToken)
      return None
    }

    parseRecord(parser, record)
  }

  protected def canBe(schema: Schema, fieldType: Schema.Type): Boolean = {
    if(fieldType == schema.getType) true
    else if(schema.getType == Type.UNION && schema.getTypes.find(_.getType == fieldType).isDefined) true
    else false
  }

  protected def parseValue(parser: JsonParser, schema: Schema, fieldname: String): Any = {
    parser.getCurrentToken match {
      case JsonToken.START_OBJECT     if(canBe(schema, Type.RECORD)) => {
        val subRecordSchema: Schema =
          if(schema.getType == Type.RECORD)
            schema
          else if(schema.getType == Type.UNION)
            schema.getTypes.find(_.getType == Type.RECORD).getOrElse(throw new RuntimeException("Unexpected subrecord"))
          else
            throw new RuntimeException("Unexpected sub record")

        val className = subRecordSchema.getNamespace + "." + subRecordSchema.getName
        val subRecordClass = Class.forName(className).asInstanceOf[Class[IndexedRecord]]
        val subRecord = subRecordClass.newInstance()

        parseRecord(parser, subRecord).getOrElse({
          if (canBe(schema, Type.NULL)) null
          else throw new RuntimeException("Record cannot be null")
        })
      }
      case JsonToken.START_ARRAY      if(canBe(schema, Type.ARRAY)) => {
        val array = new GenericData.Array[Any](1, schema)
        while(parser.nextToken != JsonToken.END_ARRAY) {
          array.add(parseValue(parser, schema.getElementType, fieldname))
        }
        array
      }
      case JsonToken.VALUE_STRING       if(canBe(schema, Type.STRING)) => new Utf8(parser.getText)
      case JsonToken.VALUE_NUMBER_INT   if(canBe(schema, Type.LONG)) => parser.getLongValue
      case JsonToken.VALUE_NUMBER_INT   if(canBe(schema, Type.INT)) => parser.getIntValue
      case JsonToken.VALUE_NUMBER_FLOAT if(canBe(schema, Type.DOUBLE)) => parser.getDoubleValue
      case JsonToken.VALUE_TRUE         if(canBe(schema, Type.BOOLEAN)) => true
      case JsonToken.VALUE_FALSE        if(canBe(schema, Type.BOOLEAN)) => false
      case JsonToken.VALUE_NULL         if(canBe(schema, Type.NULL)) => null
      case unexp => {
        val error = "Don't know how to populate field " + fieldname + ". Found: " + parser.getCurrentToken + ", Expected: " + schema
        logger.warn(error)
        throw new RuntimeException(error)
      }
    }
  }

  protected def parseRecord[RecType <: IndexedRecord](parser: JsonParser, record: RecType): Option[RecType] = {
    val schema = record.getSchema
    var missingFields = schema.getFields.filter(f => !canBe(f.schema, Type.NULL)).map(_.name)

    while(parser.nextToken != JsonToken.END_OBJECT) {
      val fieldname = parser.getCurrentName()
      val field = schema.getField(fieldname)
      parser.nextToken

      if(field == null) {
        logger.warn("Unexpected field: " + fieldname + " in " + schema)
        parser.nextToken
        return None
      } else  {
        missingFields -= field.name
        try {
          record.put(field.pos, parseValue(parser, field.schema, field.name))
        } catch {
          case e: Exception =>
            logger.error("Error in parsing value", e)
            return None
        }
      }
    }

    if(missingFields.size > 0) {
      logger.warn("Invalid record.  The following required fields are missing: " + missingFields)
      None
    }
    else
      Some(record)
  }
}
