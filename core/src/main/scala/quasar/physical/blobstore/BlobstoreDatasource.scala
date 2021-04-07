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

import quasar.api.datasource.DatasourceType
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector._
import quasar.blobstore.services.{GetService, GetServiceResource, ListService, PropsService, StatusService}
import quasar.connector.datasource.{BatchLoader, LightweightDatasource, LightweightDatasourceModule, Loader}
import quasar.qscript.InterpretedRead

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Resource
import cats.syntax.applicative._

import fs2.Stream

class BlobstoreDatasource[F[_]: Monad: MonadResourceErr, P](
    val kind: DatasourceType,
    format: DataFormat,
    statusService: StatusService[F],
    listService: ListService[F],
    propsService: PropsService[F, P],
    getService: GetServiceResource[F])
    extends LightweightDatasource[Resource[F, ?], Stream[F, ?], QueryResult[F]] {

  private def raisePathNotFound(path: ResourcePath) =
    MonadResourceErr[F].raiseError(ResourceError.pathNotFound(path))

  val loaders = NonEmptyList.of(Loader.Batch(BatchLoader.Full { (iRead: InterpretedRead[ResourcePath]) =>
    for {
      optBytes <- (converters.resourcePathToBlobPathK[Resource[F, *]] andThen getService).apply(iRead.path)
      bytes <- optBytes.map(_.pure[Resource[F, *]]).getOrElse(Resource.liftF(raisePathNotFound(iRead.path)))
      qr = QueryResult.typed[F](format, ResultData.Continuous(bytes), iRead.stages)
    } yield qr
  }))

  override def pathIsResource(path: ResourcePath): Resource[F, Boolean] =
    converters.resourcePathToBlobPathK
      .andThen(propsService)
      .mapK(Resource.liftK)
      .map(_.isDefined)
      .apply(path)

  override def prefixedChildPaths(prefixPath: ResourcePath)
      : Resource[F, Option[Stream[F, (ResourceName, ResourcePathType.Physical)]]] =
    converters.resourcePathToPrefixPathK
      .andThen(listService)
      .mapK(Resource.liftK)
      .map(_.map(_.map(converters.toResourceNameType)))
      .apply(prefixPath)

  def asDsType: LightweightDatasourceModule.DS[F] = this

  def status: StatusService[F] = statusService
}

object BlobstoreDatasource {
  def apply[F[_]: Monad: MonadResourceErr, P](
      kind: DatasourceType,
      format: DataFormat,
      statusService: StatusService[F],
      listService: ListService[F],
      propsService: PropsService[F, P],
      getService: GetServiceResource[F])
      : BlobstoreDatasource[F, P] =
    new BlobstoreDatasource(kind, format, statusService, listService, propsService, getService)

  def mk[F[_]: Monad: MonadResourceErr, P](
      kind: DatasourceType,
      format: DataFormat,
      statusService: StatusService[F],
      listService: ListService[F],
      propsService: PropsService[F, P],
      getService: GetService[F])
      : BlobstoreDatasource[F, P] =
    new BlobstoreDatasource(kind, format, statusService, listService, propsService, getService.mapF(Resource.liftF(_)))
}
