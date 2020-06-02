package com.datastax.graph

case class MigrationConfig(from: ClusterConfig, to: ClusterConfig)
case class ClusterConfig(name:String, host: String, login: Option[String], password: Option[String])
