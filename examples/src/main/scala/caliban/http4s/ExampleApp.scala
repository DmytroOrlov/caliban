package caliban.http4s

import caliban.ExampleData._
import caliban.GraphQL._
import caliban._
import caliban.http4s.ExampleApp.{Mutations, Queries, Subscriptions}
import caliban.schema.Annotations.{GQLDeprecated, GQLDescription}
import caliban.schema.GenericSchema
import caliban.wrappers.ApolloTracing.apolloTracing
import caliban.wrappers.Wrappers._
import cats.data.Kleisli
import cats.effect.Blocker
import distage.{Injector, ModuleDef, ProviderMagnet, Tag}
import izumi.distage.constructors.TraitConstructor
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.{Router, Server}
import org.http4s.{HttpRoutes, StaticFile}
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.duration._
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.macros.annotation.accessible
import zio.stream.ZStream

@accessible(">")
trait Service {
  def service: UIO[ExampleService]
}

object Service {
  def make(chars: List[Character]) =
    for {
      svc <- ExampleService.make(chars)
    } yield new Service {
      val service = IO.succeed(svc)
    }
}

@accessible(">")
trait ServiceInterp {
  def interpreter: UIO[GraphQLInterpreter[Any, CalibanError]]
}

object ServiceInterp {
  val make = for {
    service <- Service.>.service
    interpreter <- makeApi(service).interpreter
    impl <- ZIO.access[Console with Clock](interpreter.provide)
  } yield new ServiceInterp {
    val interpreter = IO.succeed(impl)
  }

  private def makeApi(service: ExampleService) =
    graphQL(
      RootResolver(
        Queries(
          args => service.getCharacters(args.origin),
          args => service.findCharacter(args.name)
        ),
        Mutations(args => service.deleteCharacter(args.name)),
        Subscriptions(service.deletedEvents)
      )
    ) @@
      maxFields(200) @@ // query analyzer that limit query fields
      maxDepth(30) @@ // query analyzer that limit query depth
      timeout(3 seconds) @@ // wrapper that fails slow queries
      printSlowQueries(500 millis) @@ // wrapper that logs slow queries
      apolloTracing // wrapper for https://github.com/apollographql/apollo-tracing
}

@accessible(">")
trait HttpApi {
  def routes: UIO[HttpRoutes[Task]]
}

object HttpApi {
  val make =
    for {
      blocker <- Util.>.blocker
      interpreter <- ServiceInterp.>.interpreter
      route = Router[Task](
        "/api/graphql" -> CORS(Http4sAdapter.makeHttpService(interpreter)),
        "/ws/graphql" -> CORS(Http4sAdapter.makeWebSocketService(interpreter)),
        "/graphiql" -> Kleisli.liftF(StaticFile.fromResource("/graphiql.html", blocker, None))
      )
    } yield new HttpApi {
      val routes = IO.succeed(route)
    }
}

@accessible(">")
trait HttpServer {
  def bindHttp: UIO[Server[Task]]
}

object HttpServer {
  val make = for {
    implicit0(rts: Runtime[Any]) <- ZIO.runtime[Any].toManaged_
    route <- HttpApi.>.routes.toManaged_
    serv <- BlazeServerBuilder[Task]
      .withHttpApp(route.orNotFound)
      .bindHttp(8088)
      .resource
      .toManaged
  } yield new HttpServer {
    val bindHttp = IO.succeed(serv)
  }
}

@accessible(">")
trait Util {
  def blocker: UIO[Blocker]
}

object Util {
  val make = for {
    impl <- ZIO.accessM[Blocking](_.blocking.blockingExecutor.map(_.asEC)).map(Blocker.liftExecutionContext)
  } yield new Util {
    val blocker = IO.succeed(impl)
  }
}

object ExampleApp extends App with GenericSchema[Console with Clock] {

  case class Queries(
      @GQLDescription("Return all characters from a given origin")
      characters: CharactersArgs => URIO[Console, List[Character]],
      @GQLDeprecated("Use `characters`")
      character: CharacterArgs => URIO[Console, Option[Character]]
  )

  case class Mutations(deleteCharacter: CharacterArgs => URIO[Console, Boolean])

  case class Subscriptions(characterDeleted: ZStream[Console, Nothing, String])

  type ExampleTask[A] = RIO[Console with Clock, A]

  implicit val roleSchema = gen[Role]
  implicit val characterSchema = gen[Character]
  implicit val characterArgsSchema = gen[CharacterArgs]
  implicit val charactersArgsSchema = gen[CharactersArgs]

  def run(args: List[String]) = {
    val makeApp = for {
      _ <- HttpServer.>.bindHttp
      _ <- ZIO.never
    } yield 0

    def provideCake[R: TraitConstructor, A: Tag](fn: R => A): ProviderMagnet[A] =
      TraitConstructor[R].provider.map(fn)

    val definition = new ModuleDef {
      make[List[Character]].fromValue(sampleCharacters)
      make[Service].fromEffect(Service.make _)
      make[Util].fromEffect(provideCake(Util.make.provide))
      make[Blocking.Service[Any]].fromValue(Blocking.Live.blocking)
      make[Console.Service[Any]].fromValue(Console.Live.console)
      make[Clock.Service[Any]].fromValue(Clock.Live.clock)
      make[ServiceInterp].fromEffect(provideCake(ServiceInterp.make.provide))
      make[HttpApi].fromEffect(provideCake(HttpApi.make.provide))
      make[HttpServer].fromResource(provideCake(HttpServer.make.provide))
      make[UIO[Int]].from(provideCake(makeApp.provide))
    }
    Injector()
      .produceGetF[Task, UIO[Int]](definition)
      .useEffect
      .catchAll(err => putStrLn(err.toString).as(1))
  }
}
