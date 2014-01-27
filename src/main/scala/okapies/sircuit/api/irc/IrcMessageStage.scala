package okapies.sircuit.api.irc

import akka.io.{HasLogging, PipelineContext, SymmetricPipelineStage, SymmetricPipePair}
import akka.util.ByteString

class IrcMessageStage extends SymmetricPipelineStage[PipelineContext, IrcMessage, String] {

  override def apply(ctx: PipelineContext) = new SymmetricPipePair[IrcMessage, String] {

    override val commandPipeline = { msg: IrcMessage =>
      ctx.singleCommand(msg.asProtocol + "\r\n")
    }

    override val eventPipeline = { line: String =>
      // ignores invalid messages silently
      IrcMessage(line).map(ctx.singleEvent).getOrElse(ctx.nothing)
    }

  }

}

/**
 * A stage that removes CR (\r) at the end of line.
 */
class RemoveCrStage extends SymmetricPipelineStage[PipelineContext, ByteString, ByteString] {

  override def apply(ctx: PipelineContext) = new SymmetricPipePair[ByteString, ByteString] {

    override val commandPipeline = ctx.singleCommand[ByteString, ByteString] _

    override val eventPipeline = { line: ByteString =>
      line.last match {
        case '\r' => ctx.singleEvent(line.init)
        case _ => ctx.singleEvent(line)
      }
    }

  }

}

/**
 * This will be used only for debugging.
 */
private[irc] class LoggingStage[A <: AnyRef] extends SymmetricPipelineStage[HasLogging, A, A] {

  override def apply(ctx: HasLogging) = new SymmetricPipePair[A, A] {

    override val commandPipeline = { cmd: A =>
      ctx.getLogger.info("send: {}", cmd.toString)
      ctx.singleCommand(cmd)
    }

    override val eventPipeline = { evt: A =>
      ctx.getLogger.info("receive: {}", evt.toString)
      ctx.singleEvent(evt)
    }

  }

}
