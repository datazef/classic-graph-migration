name := "graph-migration"

version := "0.1"

scalaVersion := "2.11.8"

// Please make sure that following DSE version matches your DSE cluster version.
val dseVersion = "6.8.0"

resolvers += "DataStax Repo" at "https://repo.datastax.com/public-repos/"
resolvers += Resolver.mavenLocal // for testing

mainClass in (Compile, packageBin) := Some("com.datastax.graph.MigrateData")

libraryDependencies ++= Seq(
  "com.datastax.dse" % "dse-spark-dependencies" % dseVersion % "provided"
)

val DSE_HOME = file("/Users/sebastien.bastard/WORK/TOOLS/DSE/dse-6.8.0/")
// find all jars in the DSE
def allDseJars = {
  val finder: PathFinder = (DSE_HOME) ** "*.jar"
  finder.get
}
// add all jars to dependancies
unmanagedJars in Compile ++= allDseJars
unmanagedJars in Test ++= allDseJars