package millfork.parser

import millfork.CompilationOptions
import millfork.assembly.mos.AssemblyLine

/**
  * @author Karol Stasiak
  */
class MosSourceLoadingQueue(initialFilenames: List[String],
                            includePath: List[String],
                            options: CompilationOptions) extends AbstractSourceLoadingQueue[AssemblyLine](initialFilenames, includePath, options) {

  override def createParser(filename: String, src: String, parentDir: String, featureConstants: Map[String, Long]): MfParser[AssemblyLine] =
    MosParser(filename, src, parentDir, options, featureConstants)

  def enqueueStandardModules(): Unit = {
    if (options.zpRegisterSize > 0) {
      moduleQueue.enqueue(() => parseModule("zp_reg", includePath, Left(None)))
    }
  }

}
