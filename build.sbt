name := "ocelot-brain"

// do not forget to change the version in `Ocelot.scala`
version := "0.21.1"

scalaVersion := "2.13.15"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-core" % "1.5.9" % "provided",
  "ch.qos.logback" % "logback-classic" % "1.5.9" % "provided",
  "com.google.guava" % "guava" % "31.1-jre",
  "commons-codec" % "commons-codec" % "1.15",
  "com.typesafe" % "config" % "1.4.2",
  "org.apache.commons" % "commons-lang3" % "3.12.0",
  "org.apache.commons" % "commons-text" % "1.10.0",
  "commons-io" % "commons-io" % "2.11.0",
  "org.ow2.asm" % "asm" % "9.5",
  "li.cil.repack.com.naef" % "OC-LuaJ" % "20220907.1" from ("https://asie.pl/javadeps/OC-LuaJ-20220907.1.jar", true),
  "li.cil.repack.com.naef" % "OC-JNLua" % "20230530.0" from ("https://asie.pl/javadeps/OC-JNLua-20230530.0.jar", true),
  "li.cil.repack.com.naef" % "OC-JNLua-Natives" % "20220928.1" from ("https://asie.pl/javadeps/OC-JNLua-Natives-20220928.1.jar", true)
)

assemblyJarName := s"ocelot-brain-${version.value}.jar"

assemblyMergeStrategy := {
  case PathList("assets", "opencomputers", "lib", _*) => MergeStrategy.preferProject
  case x =>
    val oldStrategy = assemblyMergeStrategy.value
    oldStrategy(x)
}

Global / fileInputExcludeFilter := NothingFilter.toNio
