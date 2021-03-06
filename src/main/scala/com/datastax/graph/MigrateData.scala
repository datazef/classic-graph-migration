/*
 * Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */

package com.datastax.graph

import java.util.MissingFormatArgumentException

import com.datastax.bdp.graph.spark.graphframe._
import com.datastax.bdp.graph.spark.graphframe.dsedb.CoreDseGraphFrame
import com.datastax.bdp.graph.spark.graphframe.classic.ClassicDseGraphFrame
import com.datastax.bdp.graphv2.dsedb.schema.Column.{ColumnType => CoreColumnType, Type => CoreType}
import com.datastax.bdp.graphv2.engine.GraphKeyspace
import com.datastax.bdp.graphv2.engine.GraphKeyspace.VertexLabel
import com.datastax.spark.connector.cql.CassandraConnectorConf
import org.apache.spark.sql._
import org.apache.spark.sql.cassandra._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{ArrayType, StructField, StructType}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object MigrateData {

  /**
    * this method have to be overridden to handle multi properties that a represented as array and properites with meta
    * that are represented as SparkSQL struct
    * by default meta-properties are just dropped.
    * the first of multi properties is returned
    *
    * @param rawColumn
    * @return column with selected data
    */
  def handleMultiAndMetaProperties(rawColumn: StructField) = {
    val c = col(rawColumn.name)
    rawColumn.dataType match {
      // handle mutli property with meta-properties
      // extract first first multi-property with c(0) and drop meta properties with ("value")
      case ArrayType(StructType(fields), containsNull) => c(0)("value")
      // handle mutli property without meta-properties
      case ArrayType(field, containsNull) => c(0)
      // drop meta-properties from single property
      case StructType(fields) => c("value")
      // no need to checnge type
      case _ => c
    }
  }

  /**
    * the method should be overridden to match properties renamed during schema migration
    * default implementation assumes that property names was not changed and apply only toGfName function to it
    *
    * @param property Core Graph property
    * @return property name in provided Vertex and Edge dataframe
    */
  def getClassicPropertyName(property: GraphKeyspace.PropertyKey): String = {
    DseGraphFrame.toGfName(property.name())
  }

  /**
    * this method should be overridden in case vertex ids were changed.
    * The default implementation assumes that vertex ids are the same and only call getClassicPropertyName() for them
    * The method is used only during edge migration, additional steps are needed for vertex migration
    * for example the method replace "src" column with a number of "in_" columumn for DSE core edge table.
    * @param df     to modify
    * @param label  core vertex schema to extract id structure
    * @param classic classic schema to extract classic vertex id structure
    * @param idName "src" or "dst"
    * @return dataframe with added core vertex id columns and removed idName column
    */

  def addEdgeIdColumns(df: DataFrame, label: VertexLabel, classic: ClassicDseGraphFrame, idName: String): DataFrame = {
    val vertex = classic.graphSchema.getVertex(label.name)

    var newDf = ClassicDseGraphFrame.addNaturalVertexIdColumns(df, label.name(), classic.graphSchema, col(idName))
    // we assumes that property names was not changed
    for (prop <- label.primaryPropertyKeys().asScala) {
      newDf = newDf.withColumnRenamed(getClassicPropertyName(prop), prop.column().get().name())
    }
    newDf.drop(idName)
  }

  /**
    * Load vertices separately.  The target schema should be created and modified before this call
    * For example  after applying custom rules for multi and meta properties.
    *
    * @param vertices        vertices Dataframe
    * @param coreGraphName target graph
    * @param spark           current spark session
    */
  def migrateVertices(conf: MigrationConfig, classic: ClassicDseGraphFrame, core: CoreDseGraphFrame, spark: SparkSession): Unit = {
    System.out.println("Starting vertices migration")

    val vertices = classic.V.df

    // vertex labels to enumerate
    val vertexLabels: Seq[GraphKeyspace.VertexLabel] = core.graphKeyspace.vertexLabels().asScala.toSeq
    val dfSchema = vertices.schema

    System.out.println(s"Graph contains ${vertexLabels.size} vertices")

    for (vertexLabel: GraphKeyspace.VertexLabel <- vertexLabels) {
      System.out.println(s"  -> Processing vertex with name ${vertexLabel.name()}")

      //prepare core vertex columns for this label
      val propertyColumns = vertexLabel.propertyKeys().asScala.map((property: GraphKeyspace.PropertyKey) => {
        val name: String = getClassicPropertyName(property)

        val rawColumn: StructField = dfSchema(name)
        //  drop meta and multi properties. the method can be changed to return Seq[Column] if more then one column
        // is created base on one classic property
        val finalColumn = handleMultiAndMetaProperties(rawColumn)
        // Duration type representation is changed, the line could be removed if no Duration used in schema
        val scaleColumn = durationToNanoseconds(property.column().get.`type`(), finalColumn)
        scaleColumn as DseGraphFrame.toGfName(property.name())
      })

      // filter row and columns related to the given label
      val vertexDF = vertices.filter(col(DseGraphFrame.LabelColumnName) === vertexLabel.name())
        .select(propertyColumns: _*)
      // save vertices in the core graph
      core.updateVertices(vertexLabel.name(), vertexDF)
    }
  }

  private def durationToNanoseconds(columnType: CoreColumnType, col: Column): Column = {
    if (columnType == CoreType.Duration) col * 1000000 else col
  }

  /**
    * Load edges separately.  The target schema should be created and modified before this call
    *
    * @param edges           edge Dataframe
    * @param classicSchema    old graph scheama for id conversions. classicGraph.schema() call returns it.
    * @param coreGraphName target graph
    * @param spark           current spark session
    */

  def migrateEdges(conf: MigrationConfig, classic: ClassicDseGraphFrame, core: CoreDseGraphFrame, spark: SparkSession): Unit = {
    System.out.println("Starting edges migration")

    // it could be good to cache edges here
    val edges = classic.E.df

    val dfSchema = edges.schema

    System.out.println(s"Graph contains ${core.graphKeyspace.edgeLabels().asScala.toSeq.size} edges")

    // enumerate all edge labels, actually triplets: out_vertex_label->edge_label->in_vertex_label
    for (edgeLabel <- core.graphKeyspace.edgeLabels().asScala.toSeq) {
      System.out.println(s"  -> Processing edge with name ${edgeLabel.name()}")

      val outLabelName = edgeLabel.outLabel.name()
      val edgeLabelName = edgeLabel.name()
      val inLabelName = edgeLabel.inLabel.name()
      val propertyColumns = edgeLabel.propertyKeys().asScala.map(property => {
        // classic edge internal property "id" is mapped to core "id" column
        val name = if(property.name() == "id") "id" else getClassicPropertyName(property)
        val scaleColumn = durationToNanoseconds(property.column().get.`type`(), col(name))
        scaleColumn as name
      })
      // filter data for one core DSE-DB table
      val singleEdgeTable = edges.filter(
        (col("~label") === edgeLabelName) and
          col("src").startsWith(outLabelName + ":") and
          col("dst").startsWith(inLabelName + ":"))
        .select((propertyColumns :+ col("src")) :+ col("dst"): _*)
      // replace "src" column with unpacked out_ columns
      val unpackSrcTable = addEdgeIdColumns(singleEdgeTable, edgeLabel.outLabel, classic, "src")
      // replace "dst" column with unpacked in_ columns
      val unpackDstTable = addEdgeIdColumns(unpackSrcTable, edgeLabel.inLabel, classic, "dst")

      // save edges in the core graph
      core.updateEdges(outLabelName, edgeLabelName, inLabelName, unpackDstTable)
    }
  }

  def migrate(conf: MigrationConfig, spark: SparkSession) = {
    System.out.println(s"Starting migration with configuration $conf")

    spark.setCassandraConf("from", CassandraConnectorConf.ConnectionHostParam.option(conf.from.host) + ("spark.cassandra.auth.username" -> conf.from.login) + ("spark.cassandra.auth.password" ->  conf.from.password))
    spark.setCassandraConf("to", CassandraConnectorConf.ConnectionHostParam.option(conf.to.host) + ("spark.cassandra.auth.username" -> conf.to.login) + ("spark.cassandra.auth.password" ->  conf.to.password))

    val classic = spark.dseGraph(conf.from.name, Map ("cluster" -> "from")).asInstanceOf[ClassicDseGraphFrame]
    val core = spark.dseGraph(conf.to.name, Map ("cluster" -> "to")).asInstanceOf[CoreDseGraphFrame]

    migrateVertices(conf, classic, core, spark)
    migrateEdges(conf, classic, core, spark)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName(s"Classic To Core graph migrator")
      .getOrCreate()

    val conf = spark.sparkContext.getConf

    val from = ClusterConfig(conf.get("spark.dse.cluster.migration.fromGraph", null),
      conf.get("spark.dse.cluster.migration.fromHost", null),
      conf.get("spark.dse.cluster.migration.fromUser", ""),
      conf.get("spark.dse.cluster.migration.fromPassword", "")
    )

    val to = ClusterConfig( conf.get("spark.dse.cluster.migration.toGraph", null),
      conf.get("spark.dse.cluster.migration.toHost", null),
      conf.get("spark.dse.cluster.migration.toUser", ""),
      conf.get("spark.dse.cluster.migration.toPassword", "")
    )

    try {
      if (from.host == null || from.host.isEmpty) throw new Exception("Parameter spark.dse.cluster.migration.fromHost is missing")
      if (from.name == null || from.name.isEmpty) throw new Exception("Parameter spark.dse.cluster.migration.fromGraph is missing")
      if (to.host == null || to.host.isEmpty) throw new Exception("Parameter spark.dse.cluster.migration.toHost is missing")
      if (to.name == null || to.name.isEmpty) throw new Exception("Parameter spark.dse.cluster.migration.toGraph is missing")

      migrate(MigrationConfig(from,to), spark)
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to migrate the graph: ${e.getMessage}")
    } finally {
      System.out.println("Closing Spark session")
      spark.stop()
    }
  }
}
