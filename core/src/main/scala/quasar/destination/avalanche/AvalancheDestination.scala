/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.destination.avalanche

import scala.Predef._
import scala._
import scala.util.matching.Regex

import java.time.format.DateTimeFormatter
import java.util.UUID

import quasar.api.push.RenderConfig
import quasar.api.destination.{Destination, DestinationType, ResultSink}
import quasar.api.resource._
import quasar.api.table.{ColumnType, TableColumn, TableName}
import quasar.blobstore.azure.{AzureDeleteService, AzurePutService, Expires, TenantId}
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.blobstore.paths.{BlobPath, PathElem}

import cats.ApplicativeError
import cats.data.ValidatedNel
import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._

import com.microsoft.azure.storage.blob.ContainerURL

import doobie._
import doobie.implicits._
import doobie.free.connection.createStatement

import fs2.Stream

import org.slf4s.Logging

import pathy.Path.FileName

import scalaz.NonEmptyList

import shims._

final class AvalancheDestination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
  xa: Transactor[F],
  refContainerURL: Ref[F, Expires[ContainerURL]],
  refreshToken: F[Unit],
  config: AvalancheConfig) extends Destination[F] with Logging {

  private val IngresRenderConfig = RenderConfig.Csv(
    includeHeader = false,
    offsetDateTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx"),
    offsetTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS xxx"),
    localDateTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS"),
    localDateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd"),
    localTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))

  def destinationType: DestinationType =
    AvalancheDestinationModule.destinationType

  def sinks: NonEmptyList[ResultSink[F]] = NonEmptyList(csvSink)

  private val csvSink: ResultSink[F] = ResultSink.csv[F](IngresRenderConfig) {
    case (path, columns, bytes) =>
      for {
        tableName <- Stream.eval(ensureValidTableName(path))

        cols <- ApplicativeError[Stream[F, ?], Throwable].fromEither(
          ensureValidColumns(columns).leftMap(new RuntimeException(_)))

        freshName <- Stream.eval(upload(bytes))

        _ <- Stream.eval(copy(tableName, freshName, cols)).onFinalize(deleteBlob(freshName))

      } yield ()
  }

  private def deleteBlob(freshName: FileName): F[Unit] =
    for {
      _ <- refreshToken

      containerURL <- refContainerURL.get

      deleteService = AzureDeleteService.mk[F](containerURL.value)

      _ <- deleteService(BlobPath(List(PathElem(freshName.value))))
    } yield ()

  private def copy(tableName: TableName, freshName: FileName, cols: NonEmptyList[Fragment])
      : F[Unit] = {

    val dropQuery = dropTableQuery(tableName).update

    val createQuery = createTableQuery(tableName, cols).update

    val loadQuery = copyQuery(tableName, freshName).update

    for {
      _ <- debug(s"Table creation query:\n${createQuery.sql}")

      _ <- debugRedacted(s"Load query:\n${loadQuery.sql}")

      _ <- debug(s"Drop table query:\n${dropQuery.sql}")

      load = Sync[ConnectionIO].bracket(createStatement)(st =>
        for {
          _ <- Sync[ConnectionIO].delay(st.execute(loadQuery.sql))
          count <- Sync[ConnectionIO].delay(st.getUpdateCount)
        } yield count)(st => Sync[ConnectionIO].delay(st.close))

      count <- (dropQuery.run *> createQuery.run *> load).transact(xa)

      _ <- debug(s"Finished load")

      _ <- debug(s"Load result count: $count")

      _ <- debug(s"Finished table copy")

    } yield ()
  }

  private def upload(bytes: Stream[F, Byte]): F[FileName] =
    for {
      freshNameSuffix <- Sync[F].delay(UUID.randomUUID().toString)
      freshName = s"reform-$freshNameSuffix"

      _ <- refreshToken

      containerURL <- refContainerURL.get

      put = AzurePutService.mk[F](containerURL.value)

      _ <- debug(s"Starting upload of $freshName")

      _ <- put((BlobPath(List(PathElem(freshName))), bytes))

      _ <- debug(s"Finished upload of $freshName")

    } yield FileName(freshName)

  // Ingres accepts double quotes as part of identifiers, but they must
  // be repeated twice. So we duplicate all quotes
  // More details:
  // https://docs.actian.com/avalanche/index.html#page/SQLLanguage%2FRegular_and_Delimited_Identifiers.htm%23ww414482
  private def escapeIdent(ident: String): String = {
    val escaped = ident.replace("\"", "\"\"")

    s""""$escaped""""
  }


  private def removeSingleQuotes(str: String): String =
    str.replace("'", "")

  private def ensureValidTableName(r: ResourcePath): F[TableName] =
    r match {
      case file /: ResourcePath.Root => TableName(escapeIdent(file)).pure[F]
      case _ => MonadResourceErr[F].raiseError(ResourceError.notAResource(r))
    }

  private def ensureValidColumns(columns: List[TableColumn]): Either[String, NonEmptyList[Fragment]] =
    for {
      cols0 <- columns.toNel.toRight("No columns specified")
      cols <- cols0.traverse(mkColumn(_)).toEither.leftMap(errs =>
        s"Some column types are not supported: ${mkErrorString(errs.asScalaz)}")
    } yield cols.asScalaz

  private def copyQuery(tableName: TableName, fileName: FileName): Fragment = {
    val table = tableName.name
    val clientId = removeSingleQuotes(config.azureCredentials.clientId.value)
    val clientSecret = removeSingleQuotes(config.azureCredentials.clientSecret.value)

    fr"COPY" ++ Fragment.const0(table) ++ fr0"() VWLOAD FROM " ++ abfsPath(fileName) ++
      fr"WITH" ++ pairs(
      fr"AZURE_CLIENT_ENDPOINT" -> authEndpoint(config.azureCredentials.tenantId),
      fr"AZURE_CLIENT_ID" -> Fragment.const(s"'$clientId'"),
      fr"AZURE_CLIENT_SECRET" -> Fragment.const(s"'$clientSecret'"),
      fr"FDELIM" -> fr"','",
      fr"QUOTE" -> fr"""'"'""")
  }

  private def authEndpoint(tid: TenantId): Fragment = {
    val tenant = removeSingleQuotes(tid.value)

    Fragment.const(s"'https://login.microsoftonline.com/$tenant/oauth2/token'")
  }

  private def pairs(ps: (Fragment, Fragment)*): Fragment =
    (ps map {
      case (lhs, rhs) => lhs ++ fr"=" ++ rhs
    }).toList.intercalate(fr",")

  private def abfsPath(file: FileName): Fragment = {
    val container = config.containerName.value
    val account = config.accountName.value
    val fileName = file.value

    Fragment.const(
      s"'abfs://$container@$account.dfs.core.windows.net/$fileName'")
  }

  private def createTableQuery(tableName: TableName, columns: NonEmptyList[Fragment]): Fragment =
    fr"CREATE TABLE" ++ Fragment.const(tableName.name) ++
      Fragments.parentheses(columns.intercalate(fr",")) ++ fr"with nopartition"

  private def dropTableQuery(tableName: TableName): Fragment = {
    val table = tableName.name

    fr"DROP TABLE IF EXISTS" ++ Fragment.const(table)
  }

  private def mkErrorString(errs: NonEmptyList[ColumnType.Scalar]): String =
    errs
      .map(err => s"Column of type ${err.show} is not supported by Avalanche")
      .intercalate(", ")

  private def mkColumn(c: TableColumn): ValidatedNel[ColumnType.Scalar, Fragment] =
    columnTypeToAvalanche(c.tpe).map(Fragment.const(escapeIdent(c.name)) ++ _)

  private def columnTypeToAvalanche(ct: ColumnType.Scalar)
      : ValidatedNel[ColumnType.Scalar, Fragment] =
    ct match {
      case ColumnType.Null => fr0"INTEGER1".validNel
      case ColumnType.Boolean => fr0"BOOLEAN".validNel
      case ColumnType.LocalTime => fr0"TIME(3)".validNel
      case ColumnType.OffsetTime => fr0"TIME(3) WITH TIME ZONE".validNel
      case ColumnType.LocalDate => fr0"ANSIDATE".validNel
      case od @ ColumnType.OffsetDate => od.invalidNel
      case ColumnType.LocalDateTime => fr0"TIMESTAMP(3)".validNel
      case ColumnType.OffsetDateTime => fr0"TIMESTAMP(3) WITH TIME ZONE".validNel
      // Avalanche supports intervals, but not ISO 8601 intervals, which is what
      // Quasar produces
      case i @ ColumnType.Interval => i.invalidNel
      case ColumnType.Number => fr0"DECIMAL(33, 3)".validNel
      case ColumnType.String => fr0"NVARCHAR(512)".validNel
    }

  private def debug(msg: String): F[Unit] =
    Sync[F].delay(log.debug(msg))

  private def debugRedacted(msg: String): F[Unit] = {
    // remove everything within single quotes, to avoid leaking credentials
    Sync[F].delay(
      log.debug((new Regex("""'[^' ]{2,}'""")).replaceAllIn(msg, "<REDACTED>")))
  }
}
