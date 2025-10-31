import Dependencies.*
import sbt.*
import sbt.Keys.*

ThisBuild / organization := "xyz.kd5ujc"
ThisBuild / scalaVersion := "2.13.16"
ThisBuild / evictionErrorLevel := Level.Warn

ThisBuild / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
  case PathList("xyz", "kd5ujc", "buildinfo", xs @ _*) => MergeStrategy.first
  case PathList(xs@_*) if xs.last == "module-info.class" => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  resolvers ++= Seq(
    Resolver.mavenLocal
  ),
  libraryDependencies ++= Seq(
    CompilerPlugin.kindProjector,
    CompilerPlugin.betterMonadicFor,
    CompilerPlugin.semanticDB,
    Libraries.cats,
    Libraries.catsEffect,
    Libraries.enumeratum,
    Libraries.enumeratumCirce,
    Libraries.declineCore,
    Libraries.declineEffect,
    Libraries.declineRefined,
    Libraries.metakit,
    Libraries.pureconfigCore,
    Libraries.pureconfigCats
  )
) ++ Defaults.itSettings

lazy val commonTestSettings = Seq(
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
      Libraries.catsEffectTestkit,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
  ).map(_ % Test)
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion
  ),
  buildInfoPackage := "xyz.kd5ujc.buildinfo"
)

lazy val root = (project in file("."))
  .settings(
    name := "workchain"
  ).aggregate(models, sharedData, currencyL0, currencyL1, dataL1)

lazy val models = (project in file("modules/models"))
  .settings(
    commonSettings,
    name := "workchain-models"
  )

lazy val sharedTest = (project in file("modules/shared-test"))
  .dependsOn(models)
  .settings(
    commonSettings,
    commonTestSettings,
    name := "workchain-shared-test",
  )

lazy val sharedData = (project in file("modules/shared-data"))
  .dependsOn(sharedTest, models)
  .settings(
    commonSettings,
    commonTestSettings,
    name := "workchain-shared-data",
  )

lazy val currencyL0 = (project in file("modules/l0"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "workchain-currency-l0"
  )

lazy val currencyL1 = (project in file("modules/l1"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "workchain-currency-l1"
  )

lazy val dataL1 = (project in file("modules/data_l1"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "workchain-data-l1"
  )