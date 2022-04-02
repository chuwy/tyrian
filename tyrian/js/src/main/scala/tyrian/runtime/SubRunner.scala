package tyrian.runtime

// import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import cats.effect.kernel.Async
import cats.syntax.all.*
import tyrian.Sub

import scala.annotation.nowarn
import scala.annotation.tailrec

/** Subscriptions are declared as a function with a model argument, that means, that the currently active subscriptions
  * can change over the course of the applications life depending on the state of the model. So when we run the Sub, we
  * need to record the active subs, instantiate new ones, and clean up old ones.
  */
final class SubRunner[F[_]: Async]:

  // The currently live subs.
  @SuppressWarnings(Array("scalafix:DisableSyntax.var"))
  private var currentSubscriptions: List[(String, F[Unit])] = Nil
  // This is a queue of new subs waiting to be run for the first time.
  // In the event that two events happen at once, you can't assume that
  // you would have run all the subs between events.
  @SuppressWarnings(Array("scalafix:DisableSyntax.var"))
  private var aboutToRunSubscriptions: Set[String] = Set.empty

  @SuppressWarnings(Array("scalafix:DisableSyntax.throw"))
  def runSub[Msg](
      sub: Sub[F, Msg],
      callback: Msg => Unit,
      async: (=> Unit) => Unit
  ): F[Unit] =
    // Flatten all the subs into a list of indvidual subs.
    val allSubs = {
      @tailrec
      @nowarn
      def rec(remaining: List[Sub[F, Msg]], acc: List[Sub.OfObservable[F, _, Msg]]): List[Sub.OfObservable[F, _, Msg]] =
        remaining match
          case l if l.isEmpty =>
            acc

          case Sub.Empty() :: ss =>
            rec(ss, acc)

          case Sub.Combine(s1, s2) :: ss =>
            rec(s1 :: s1 :: ss, acc)

          case Sub.Batch(sbs) :: ss =>
            rec(sbs ++ ss, acc)

          case (s: Sub.OfObservable[F, _, _]) :: ss =>
            rec(ss, s.asInstanceOf[Sub.OfObservable[F, _, Msg]] :: acc)

      rec(List(sub), Nil)
    }

    // Any 'active' subs not in the new list should be discarded
    val (stillActives, discarded) =
      currentSubscriptions.partition { case (id, _) => allSubs.exists(_.id == id) }

    // Work out the new ones, i.e. in the still active or in the 'first run' queue
    val newSubs =
      allSubs.filter(s => stillActives.forall(_._1 != s.id) && !aboutToRunSubscriptions.contains(s.id))

    // Update the first run queue
    aboutToRunSubscriptions = aboutToRunSubscriptions ++ newSubs.map(_.id)
    // Update the current subs
    currentSubscriptions = stillActives

    val cb: Either[Throwable, Msg] => Unit = {
      case Left(e)  => throw e
      case Right(m) => async(callback(m))
    }

    val discard = discarded.map(_._2).sequence.map(_ => ())
    val runNext =
      newSubs
        .map { case Sub.OfObservable(id, observable, toMsg) =>
          // Fire off the new sub
          val task = Async[F].map(observable) { run =>
            run {
              case Left(e)  => throw e
              case Right(m) => callback(toMsg(m))
            }
          }
          val runnable = Async[F]
            .map(task) { cancel =>
              // Remove from the queue
              aboutToRunSubscriptions = aboutToRunSubscriptions - id
              // Add to the current subs
              currentSubscriptions = (id -> cancel) :: currentSubscriptions
            }

          runnable
        }
        .sequence
        .map(_ => ())

    for {
      _ <- discard
      _ <- runNext
    } yield ()
// Async[F].delay(())
