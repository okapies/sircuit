package okapies.sircuit.irc

import akka.io.{PipelineContext, SymmetricPipelineStage, SymmetricPipePair}

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
