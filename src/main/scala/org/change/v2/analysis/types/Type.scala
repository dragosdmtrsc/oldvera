package org.change.v2.analysis.types

import org.change.v2.interval._
import com.fasterxml.jackson.annotation.JsonTypeInfo

trait Type {
  def name: String
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME, 
  include = JsonTypeInfo.As.PROPERTY, 
  property = "type")
trait NumericType extends Type {
  override def name = "GenericNumeric"
  def min: Long = 0
  def max: Long = Long.MaxValue
  def admissibleRange: Interval = (min, max)
  def admissibleSet: ValueSet = List(admissibleRange)
  override def toString = name
}

object LongType extends NumericType
