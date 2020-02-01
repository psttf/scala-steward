package org.scalasteward.core.update

import cats.implicits._
import org.scalasteward.core.TestSyntax._
import org.scalasteward.core.data.GroupId
import org.scalasteward.core.data.Update.Single
import org.scalasteward.core.mock.MockContext.filterAlg
import org.scalasteward.core.mock.MockState
import org.scalasteward.core.repoconfig.{RepoConfig, UpdatePattern, UpdatesConfig}
import org.scalasteward.core.update.FilterAlg.{BadVersions, NoSuitableNextVersion}
import org.scalasteward.core.util.Nel
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FilterAlgTest extends AnyFunSuite with Matchers {
  test("globalFilter: SNAP -> SNAP") {
    val update = Single("org.scalatest" % "scalatest" % "3.0.8-SNAP2", Nel.of("3.0.8-SNAP10"))
    FilterAlg.globalFilter(update) shouldBe Right(update)
  }

  test("globalFilter: RC -> SNAP") {
    val update = Single("org.scalatest" % "scalatest" % "3.0.8-RC2", Nel.of("3.1.0-SNAP10"))
    FilterAlg.globalFilter(update) shouldBe Left(NoSuitableNextVersion(update))
  }

  test("globalFilter: update without bad version") {
    val update = Single("com.jsuereth" % "sbt-pgp" % "1.1.0", Nel.of("1.1.2", "2.0.0"))
    FilterAlg.globalFilter(update) shouldBe Right(update.copy(newerVersions = Nel.of("1.1.2")))
  }

  test("globalFilter: update with bad version") {
    val update = Single("com.jsuereth" % "sbt-pgp" % "1.1.2-1", Nel.of("1.1.2", "2.0.0"))
    FilterAlg.globalFilter(update) shouldBe Right(update.copy(newerVersions = Nel.of("2.0.0")))
  }

  test("globalFilter: update with bad version 2") {
    val update =
      Single(
        "net.sourceforge.plantuml" % "plantuml" % "1.2019.11",
        Nel.of("7726", "8020", "2017.09", "1.2019.12")
      )
    FilterAlg.globalFilter(update) shouldBe Right(update.copy(newerVersions = Nel.of("1.2019.12")))
  }

  test("globalFilter: update with only bad versions") {
    val update = Single("org.http4s" % "http4s-dsl" % "0.18.0", Nel.of("0.19.0"))
    FilterAlg.globalFilter(update) shouldBe Left(BadVersions(update))
  }

  test("globalFilter: update to pre-release of a different series") {
    val update = Single("com.jsuereth" % "sbt-pgp" % "1.1.2-1", Nel.of("2.0.1-M3"))
    FilterAlg.globalFilter(update) shouldBe Left(NoSuitableNextVersion(update))
  }

  test("ignore update via config updates.ignore") {
    val update1 = Single("org.http4s" % "http4s-dsl" % "0.17.0", Nel.of("0.18.0"))
    val update2 = Single("eu.timepit" % "refined" % "0.8.0", Nel.of("0.8.1"))
    val config = RepoConfig(
      UpdatesConfig(ignore = List(UpdatePattern(GroupId("eu.timepit"), Some("refined"), None)))
    )

    val initialState = MockState.empty
    val (state, filtered) =
      filterAlg.localFilterMany(config, List(update1, update2)).run(initialState).unsafeRunSync()

    filtered shouldBe List(update1)
    state shouldBe initialState.copy(
      logs = Vector(
        (None, "Ignore eu.timepit:refined : 0.8.0 -> 0.8.1 (reason: ignored by config)")
      )
    )
  }

  test("ignore update via config updates.allow") {
    val update1 = Single("org.http4s" % "http4s-dsl" % "0.17.0", Nel.of("0.18.0"))
    val update2 = Single("eu.timepit" % "refined" % "0.8.0", Nel.of("0.8.1"))

    val config = RepoConfig(
      updates = UpdatesConfig(
        allow = List(
          UpdatePattern(update1.groupId, None, Some("0.17")),
          UpdatePattern(update2.groupId, Some("refined"), Some("0.8"))
        )
      )
    )

    val filtered = filterAlg
      .localFilterMany(config, List(update1, update2))
      .runA(MockState.empty)
      .unsafeRunSync()

    filtered shouldBe List(update2)
  }

  test("ignore update via config updates.allow using suffix") {
    val update =
      Single(
        "com.microsoft.sqlserver" % "mssql-jdbc" % "7.2.2.jre8",
        Nel.of("7.2.2.jre11", "7.3.0.jre8", "7.3.0.jre11")
      )

    val config = RepoConfig(
      updates = UpdatesConfig(
        allow = List(
          UpdatePattern(update.groupId, Some(update.artifactId.name), Some(".*jre8"))
        )
      )
    )

    val filtered = filterAlg
      .localFilterMany(config, List(update))
      .runA(MockState.empty)
      .unsafeRunSync()

    filtered shouldBe List(update.copy(newerVersions = Nel.of("7.3.0.jre8")))
  }

  test("ignore update via config updates.ignore using suffix") {
    val update =
      Single(
        "com.microsoft.sqlserver" % "mssql-jdbc" % "7.2.2.jre8",
        Nel.of("7.2.2.jre11", "7.3.0.jre8", "7.3.0.jre11")
      )

    val config = RepoConfig(
      updates = UpdatesConfig(
        ignore = List(
          UpdatePattern(update.groupId, Some(update.artifactId.name), Some(".*jre11"))
        )
      )
    )

    val filtered = filterAlg
      .localFilterMany(config, List(update))
      .runA(MockState.empty)
      .unsafeRunSync()

    filtered shouldBe List(update.copy(newerVersions = Nel.of("7.3.0.jre8")))
  }
}
