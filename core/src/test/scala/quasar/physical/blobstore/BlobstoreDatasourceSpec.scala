/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.blobstore

import slamdata.Predef._
import quasar.ScalarStages
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.{QueryResult, ResourceError}
import quasar.connector.datasource.DatasourceModule
import quasar.qscript.InterpretedRead

import java.nio.charset.StandardCharsets

import cats.effect.{Effect, IO, Resource}
import cats.effect.testing.specs2.CatsIO
import cats.syntax.applicative._
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

abstract class BlobstoreDatasourceSpec extends Specification with CatsIO {

  def datasource: Resource[IO, DatasourceModule.DS[IO]]

  val nonExistentPath =
    ResourcePath.root() / ResourceName("does") / ResourceName("not") / ResourceName("exist")

  val spanishResourceName1 = ResourceName("El veloz murciélago hindú")
  val spanishResourcePrefix = ResourcePath.root() / ResourceName("testData") / spanishResourceName1 / ResourceName("comía feliz cardillo y kiwi") / ResourceName("La cigüeña tocaba el saxofón")
  val spanishResourceLeaf = ResourceName("detrás del palenque de paja")
  val spanishResource = spanishResourcePrefix / spanishResourceLeaf

  "prefixedChildPaths" >> {

    "list nested children" >> {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1") / ResourceName("dir2") / ResourceName("dir3"),
        Set(ResourceName("flattenable.data") -> ResourcePathType.leafResource))
    }

    "list nested children (alternative)" >> {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("dir1") / ResourceName("dir2") / ResourceName("dir3") / ResourceName(""),
        Set(ResourceName("flattenable.data") -> ResourcePathType.leafResource))
    }

    "list nested children 2" >> {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("prefix3") / ResourceName("subprefix5"),
        Set(ResourceName("cars2.data") -> ResourcePathType.leafResource))
    }

    "list children at the root of the bucket" >> {
      assertPrefixedChildPaths(
        ResourcePath.root(),
        Set(
          ResourceName("extraSmallZips.data") -> ResourcePathType.leafResource,
          ResourceName("dir1") -> ResourcePathType.prefix,
          ResourceName("prefix3") -> ResourcePathType.prefix,
          ResourceName("testdata") -> ResourcePathType.prefix))
    }

    "list children at the root of the bucket (alternative)" >> {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName(""),
        Set(
          ResourceName("extraSmallZips.data") -> ResourcePathType.leafResource,
          ResourceName("dir1") -> ResourcePathType.prefix,
          ResourceName("prefix3") -> ResourcePathType.prefix,
          ResourceName("testdata") -> ResourcePathType.prefix))
    }

    "return empty stream for non-prefix path" >> {
      assertPrefixedChildPaths(
        ResourcePath.root() / ResourceName("extraSmallZips.data"),
        Set())
    }

    // doesn't adhere to datasource spec see ch11270
    "return empty stream for non-existing path" >> {
      assertPrefixedChildPaths(nonExistentPath, Set())
    }
  }

  "evaluate" >> {
    "read line-delimited JSON" >> {
      assertResultBytes(
        datasource,
        ResourcePath.root() / ResourceName("testdata") / ResourceName("lines.json"),
        "[1, 2]\n[3, 4]\n".getBytes(StandardCharsets.UTF_8))
    }

    "read array JSON" >> {
      assertResultBytes(
        datasource,
        ResourcePath.root() / ResourceName("testdata") / ResourceName("array.json"),
        "[[1, 2], [3, 4]]\n".getBytes(StandardCharsets.UTF_8))
    }

    "reading a non-existent file raises ResourceError.PathNotFound" >> {
      assertPathNotFound(
        datasource,
        ResourcePath.root() / ResourceName("does-not-exist")
      )
    }
  }

  "pathIsResource" >> {
    "the root of a bucket with a trailing slash is not a resource" >> {
      assertPathIsResource(
        datasource,
        ResourcePath.root() / ResourceName(""),
        false)
    }

    "the root of a bucket is not a resource" >> {
      assertPathIsResource(
        datasource,
        ResourcePath.root(),
        false)
    }

    "a prefix without contents is not a resource" >> {
      assertPathIsResource(
        datasource,
        ResourcePath.root() / ResourceName("testdata"),
        false)
    }

    "an actual file is a resource" >> {
      assertPathIsResource(
        datasource,
        ResourcePath.root() / ResourceName("testdata") / ResourceName("array.json"),
        true)
    }
  }

  def iRead[A](path: A): InterpretedRead[A] = InterpretedRead(path, ScalarStages.Id)

  def assertPathIsResource(
      datasource: Resource[IO, DatasourceModule.DS[IO]],
      path: ResourcePath,
      expected: Boolean)
      : IO[MatchResult[Any]] =
    datasource.use(_.pathIsResource(path).use(b => IO.pure(b must_=== expected)))

  def assertPathNotFound(
      datasource: Resource[IO, DatasourceModule.DS[IO]],
      path: ResourcePath)
      : IO[MatchResult[Any]] =
    datasource use { ds =>
      Effect[IO].attempt(ds.loadFull(iRead(path)).value.use(IO.pure)) map {
        case Left(t) => ResourceError.throwableP.getOption(t) must_=== Some(ResourceError.pathNotFound(path))
        case Right(r) => ko(s"Unexpected QueryResult: $r")
      }
    }

  def assertPrefixedChildPaths(path: ResourcePath, expected: Set[(ResourceName, ResourcePathType)]) =
    datasource.use(_.prefixedChildPaths(path) use {
      case Some(children) => children.compile.to(Set).map(_ must_== expected)
      case None => IO.raiseError[MatchResult[Any]](new Exception(s"Failed to list resources under $path"))
    })

  def assertPrefixedChildPathsNone(path: ResourcePath) =
    datasource.use(_.prefixedChildPaths(path).use(r => IO.pure(r must beNone)))

  def assertResultBytes(
      datasource: Resource[IO, DatasourceModule.DS[IO]],
      path: ResourcePath,
      expected: Array[Byte]): IO[MatchResult[Any]] =
    datasource use { ds =>
      ds.loadFull(iRead(path)).value use {
        case Some(QueryResult.Typed(_, data, ScalarStages.Id)) =>
          data.data.compile.to(Array).map(_ must_=== expected)

        case _ =>
          ko("Unexpected QueryResult").pure[IO]
      }
    }
}
