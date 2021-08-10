package totoro.ocelot.brain.entity.machine.debug

abstract class StackFrame

trait Debuggable {
  // Retrieve a list of stack frames in the main thread
  def getStackFrames: Option[List[StackFrame]]

  // Enable / disable the debug mode
  def setDebugEnabled(enabled: Boolean): Unit

  // Request to pause the execution for debugging
  // In addition, this method enables the debug mode if it was disabled
  def break(): Unit

  // Continue the execution after a previous stoppage
  def resume(): Unit

  // Advance the execution by a single line
  def step(): Unit

  // Advance the execution by a single instruction
  def stepi(): Unit
}
