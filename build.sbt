name := "ocelot-brain"

// do not forget to change the version in `Ocelot.scala`
version := "0.24.2"

scalaVersion := "2.13.11"

libraryDependencies ++= Seq(
  "org.apache.logging.log4j" % "log4j-core" % "2.26.1",
  "org.apache.logging.log4j" % "log4j-api" % "2.26.1",
  "com.google.guava" % "guava" % "33.7.1-jre",
  "commons-codec" % "commons-codec" % "1.22.1",
  "com.typesafe" % "config" % "1.4.9",
  "org.apache.commons" % "commons-lang3" % "3.20.0",
  "org.apache.commons" % "commons-text" % "1.15.0",
  "commons-io" % "commons-io" % "2.22.0",
  "org.ow2.asm" % "asm" % "9.10.1",
  "li.cil.repack.com.naef" % "OC-LuaJ" % "20220907.1" from ("https://asie.pl/javadeps/OC-LuaJ-20220907.1.jar", true),
  "li.cil.repack.com.naef" % "OC-JNLua" % "20230530.0" from ("https://asie.pl/javadeps/OC-JNLua-20230530.0.jar", true),
  "li.cil.repack.com.naef" % "OC-JNLua-Natives" % "20220928.1" from ("https://asie.pl/javadeps/OC-JNLua-Natives-20220928.1.jar", true)
)

assemblyJarName := s"ocelot-brain-${version.value}.jar"

assemblyMergeStrategy := {
  case PathList("assets", "opencomputers", "lib", _*) => MergeStrategy.preferProject
  case _ => MergeStrategy.discard
}

Global / fileInputExcludeFilter := NothingFilter.toNio
