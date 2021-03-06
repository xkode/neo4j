/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import java.util.UUID

import org.neo4j.collection.primitive.PrimitiveLongSet
import org.neo4j.cypher.internal.runtime.{QueryContext, QueryStatistics}
import org.neo4j.cypher.internal.util.v3_4.ParameterNotFoundException
import org.neo4j.cypher.internal.runtime.interpreted.{ExecutionContext, MutableMaps}
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.PathValueBuilder
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates.{InCheckContainer, SingleThreadedLRUCache}
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.MapValue

import scala.collection.mutable

class QueryState(val query: QueryContext,
                 val resources: ExternalCSVResource,
                 val params: MapValue,
                 val decorator: PipeDecorator = NullPipeDecorator,
                 val timeReader: TimeReader = new TimeReader,
                 val initialContext: Option[ExecutionContext] = None,
                 val queryId: AnyRef = UUID.randomUUID().toString,
                 val triadicState: mutable.Map[String, PrimitiveLongSet] = mutable.Map.empty,
                 val repeatableReads: mutable.Map[Pipe, Seq[ExecutionContext]] = mutable.Map.empty,
                 val cachedIn: SingleThreadedLRUCache[Any, InCheckContainer] =
                   new SingleThreadedLRUCache(maxSize = 16)) {
  private var _pathValueBuilder: PathValueBuilder = _

  def createOrGetInitialContext(factory: ExecutionContextFactory): ExecutionContext =
    initialContext.getOrElse(ExecutionContext.empty)

  def newExecutionContext(factory: ExecutionContextFactory): ExecutionContext = {
    initialContext match {
      case Some(init) => factory.copyWith(init)
      case None => factory.newExecutionContext()
    }
  }

  def clearPathValueBuilder: PathValueBuilder = {
    if (_pathValueBuilder == null) {
      _pathValueBuilder = new PathValueBuilder()
    }
    _pathValueBuilder.clear()
  }

  def readTimeStamp(): Long = timeReader.getTime

  def  getParam(key: String): AnyValue = {
    if (!params.containsKey(key)) throw new ParameterNotFoundException("Expected a parameter named " + key)
    params.get(key)
  }

  def getStatistics: QueryStatistics = query.getOptStatistics.getOrElse(QueryState.defaultStatistics)

  def withDecorator(decorator: PipeDecorator) =
    new QueryState(query, resources, params, decorator, timeReader, initialContext, queryId, triadicState, repeatableReads, cachedIn)

  def withInitialContext(initialContext: ExecutionContext) =
    new QueryState(query, resources, params, decorator, timeReader, Some(initialContext), queryId, triadicState, repeatableReads, cachedIn)

  /**
    * When running on the RHS of an Apply, this method will fill an execution context with argument data
    * @param ctx ExecutionContext to fill with data
    */
  def copyArgumentStateTo(ctx: ExecutionContext, nLongs: Int, nRefs: Int): Unit = initialContext.foreach(initData => ctx.copyFrom(initData, nLongs, nRefs))

  def copyArgumentStateTo(ctx: ExecutionContext): Unit = initialContext.foreach(initData => initData.copyTo(ctx))

  def withQueryContext(query: QueryContext) =
    new QueryState(query, resources, params, decorator, timeReader, initialContext, queryId, triadicState, repeatableReads, cachedIn)
}

object QueryState {
  val defaultStatistics = QueryStatistics()
}

class TimeReader {
  lazy val getTime: Long = System.currentTimeMillis()
}

trait ExecutionContextFactory {
  def newExecutionContext(m: mutable.Map[String, AnyValue] = MutableMaps.empty): ExecutionContext
  def newExecutionContext(): ExecutionContext
  def copyWith(init: ExecutionContext): ExecutionContext
  def copyWith(row: ExecutionContext, newEntries: Seq[(String, AnyValue)]): ExecutionContext
  def copyWith(row: ExecutionContext, key: String, value: AnyValue): ExecutionContext
  def copyWith(row: ExecutionContext, key1: String, value1: AnyValue, key2: String, value2: AnyValue): ExecutionContext
  def copyWith(row: ExecutionContext,
               key1: String, value1: AnyValue,
               key2: String, value2: AnyValue,
               key3: String, value3: AnyValue): ExecutionContext
}

case class CommunityExecutionContextFactory() extends ExecutionContextFactory {
  override def newExecutionContext(m: mutable.Map[String, AnyValue] = MutableMaps.empty): ExecutionContext =
    ExecutionContext(m)

  override def newExecutionContext(): ExecutionContext = ExecutionContext.empty

  // As community execution ctxs are immutable, we can simply return init here.
  override def copyWith(init: ExecutionContext): ExecutionContext = init

  override def copyWith(row: ExecutionContext, newEntries: Seq[(String, AnyValue)]): ExecutionContext =
    row.copyWith(newEntries)

  override def copyWith(row: ExecutionContext, key: String, value: AnyValue): ExecutionContext =
    row.copyWith(key, value)

  override def copyWith(row: ExecutionContext,
                        key1: String, value1: AnyValue,
                        key2: String, value2: AnyValue): ExecutionContext =
    row.copyWith(key1, value1, key2, value2)

  override def copyWith(row: ExecutionContext,
                        key1: String, value1: AnyValue,
                        key2: String, value2: AnyValue,
                        key3: String, value3: AnyValue): ExecutionContext =
    row.copyWith(key1, value1, key2, value2, key3, value3)
}
