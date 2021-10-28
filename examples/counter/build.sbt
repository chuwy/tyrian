ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"

lazy val root = (project in file("."))
  .dependsOn(tyrian)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion                    := "3.1.0",
    name                            := "tyrian-counter-example",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalafixOnCompile                       := true,
    semanticdbEnabled                       := true,
    semanticdbVersion                       := scalafixSemanticdb.revision
  )

lazy val tyrian = ProjectRef(file("../.."), "tyrian")
