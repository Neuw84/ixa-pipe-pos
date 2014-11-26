/*
 * Copyright 2014 Rodrigo Agerri

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package es.ehu.si.ixa.ixa.pipe.pos;

import ixa.kaflib.KAFDocument;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;
import opennlp.tools.cmdline.CmdLineUtil;
import opennlp.tools.postag.POSModel;
import opennlp.tools.util.TrainingParameters;

import org.jdom2.JDOMException;

import com.google.common.io.Files;

import es.ehu.si.ixa.ixa.pipe.lemmatize.DictionaryLemmatizer;
import es.ehu.si.ixa.ixa.pipe.lemmatize.MorfologikLemmatizer;
import es.ehu.si.ixa.ixa.pipe.lemmatize.SimpleLemmatizer;
import es.ehu.si.ixa.ixa.pipe.pos.eval.Evaluate;
import es.ehu.si.ixa.ixa.pipe.pos.train.BaselineTrainer;
import es.ehu.si.ixa.ixa.pipe.pos.train.DefaultTrainer;
import es.ehu.si.ixa.ixa.pipe.pos.train.Flags;
import es.ehu.si.ixa.ixa.pipe.pos.train.InputOutputUtils;
import es.ehu.si.ixa.ixa.pipe.pos.train.Trainer;

/**
 * Main class of ixa-pipe-pos, the pos tagger of ixa-pipes
 * (ixa2.si.ehu.es/ixa-pipes). The annotate method is the main entry point.
 *
 * @author ragerri
 * @version 2014-07-08
 */

public class CLI {

  /**
   * Get dynamically the version of ixa-pipe-pos by looking at the MANIFEST
   * file.
   */
  private final String version = CLI.class.getPackage()
      .getImplementationVersion();
  /**
   * The CLI arguments.
   */
  private Namespace parsedArguments = null;
  /**
   * The argument parser.
   */
  private ArgumentParser argParser = ArgumentParsers.newArgumentParser(
      "ixa-pipe-pos-" + version + ".jar").description(
      "ixa-pipe-pos-" + version
          + " is a multilingual POS tagger developed by IXA NLP Group.\n");
  /**
   * Sub parser instance.
   */
  private Subparsers subParsers = argParser.addSubparsers().help(
      "sub-command help");
  /**
   * The parser that manages the tagging sub-command.
   */
  private Subparser annotateParser;
  /**
   * The parser that manages the training sub-command.
   */
  private Subparser trainParser;
  /**
   * The parser that manages the evaluation sub-command.
   */
  private Subparser evalParser;
  /**
   * Default beam size for decoding.
   */
  public static final int DEFAULT_BEAM_SIZE = 3;

  /**
   * Construct a CLI object with the three sub-parsers to manage the command
   * line parameters.
   */
  public CLI() {
    annotateParser = subParsers.addParser("tag").help("Tagging CLI");
    loadAnnotateParameters();
    trainParser = subParsers.addParser("train").help("Training CLI");
    loadTrainingParameters();
    evalParser = subParsers.addParser("eval").help("Evaluation CLI");
    loadEvalParameters();
  }

  /**
   * The main method.
   *
   * @param args
   *          the arguments
   * @throws IOException
   *           the input output exception if not file is available
   * @throws JDOMException
   *           as the input is a NAF file, a JDOMException could be thrown
   */
  public static void main(final String[] args) throws IOException,
      JDOMException {

    CLI cmdLine = new CLI();
    cmdLine.parseCLI(args);
  }

  /**
   * Parse the command interface parameters with the argParser.
   *
   * @param args
   *          the arguments passed through the CLI
   * @throws IOException
   *           exception if problems with the incoming data
   * @throws JDOMException 
   * @throws JWNLException
   */
  public final void parseCLI(final String[] args) throws IOException, JDOMException {
    try {
      parsedArguments = argParser.parseArgs(args);
      System.err.println("CLI options: " + parsedArguments);
      if (args[0].equals("tag")) {
        annotate(System.in, System.out);
      } else if (args[0].equals("eval")) {
        eval();
      } else if (args[0].equals("train")) {
        train();
      }
    } catch (ArgumentParserException e) {
      argParser.handleError(e);
      System.out.println("Run java -jar target/ixa-pipe-pos-" + version
          + ".jar (tag|train|eval) -help for details");
      System.exit(1);
    }
  }

  /**
   * Main entry point for annotation. Takes system.in as input and outputs
   * annotated text via system.out.
   *
   * @param inputStream
   *          the input stream
   * @param outputStream
   *          the output stream
   * @throws IOException
   *           the exception if not input is provided
   */
  public final void annotate(final InputStream inputStream,
      final OutputStream outputStream) throws IOException, JDOMException {

    int beamsize = parsedArguments.getInt("beamsize");
    String model;
    if (parsedArguments.get("model") == null) {
      model = "baseline";
    } else {
      model = parsedArguments.getString("model");
    }
    String lemMethod = parsedArguments.getString("lemmatize");
    BufferedReader breader = null;
    BufferedWriter bwriter = null;
    breader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    bwriter = new BufferedWriter(new OutputStreamWriter(System.out, "UTF-8"));

    KAFDocument kaf = KAFDocument.createFromStream(breader);
    String lang;
    if (parsedArguments.get("lang") == null) {
      lang = kaf.getLang();
    } else {
      lang = parsedArguments.getString("lang");
    }
    DictionaryLemmatizer lemmatizer = null;
    // TODO static loading of dictionaries
    Resources resourceRetriever = new Resources();
    if (lemMethod.equalsIgnoreCase("plain")) {
      System.err.println("Using plain text dictionary");
      InputStream dictLemmatizer = resourceRetriever.getDictionary(lang);
      lemmatizer = new SimpleLemmatizer(dictLemmatizer, lang);
    } else {
      System.err.println("Using default Morfologik binary dictionary.");
      URL dictLemmatizer = resourceRetriever.getBinaryDict(lang);
      lemmatizer = new MorfologikLemmatizer(dictLemmatizer, lang);
    }
    Annotate annotator = new Annotate(lang, model, beamsize);
    // annotate to KAF
    if (parsedArguments.getBoolean("nokaf")) {
      KAFDocument.LinguisticProcessor newLp = kaf.addLinguisticProcessor(
          "terms", "ixa-pipe-pos-" + lang, version);

      newLp.setBeginTimestamp();
      annotator.annotatePOSToKAF(kaf, lemmatizer);
      newLp.setEndTimestamp();
      bwriter.write(kaf.toString());
    } else {
      // annotate to CoNLL
      bwriter.write(annotator.annotatePOSToCoNLL(kaf, lemmatizer));
    }
    bwriter.close();
    breader.close();
  }

  /**
   * Generate the annotation parameter of the CLI.
   */
  private void loadAnnotateParameters() {
    annotateParser.addArgument("-l", "--lang")
        .choices("en", "es", "it")
        .required(false)
        .help("Choose a language to perform annotation with ixa-pipe-pos.");
    annotateParser.addArgument("-m", "--model")
        .required(true)
        .help("Choose model to perform POS tagging.");
    annotateParser.addArgument("--beamsize")
        .setDefault(DEFAULT_BEAM_SIZE)
        .type(Integer.class)
        .help("Choose beam size for decoding, it defaults to 3.");
    annotateParser
        .addArgument("-lem", "--lemmatize")
        .choices("bin", "plain")
        .setDefault("bin")
        .help("Lemmatization method: Choose 'bin' for binary Morfologik "
        + " dictionary (default), 'plain' for plain text dictionary.\n");
    annotateParser
        .addArgument("--nokaf")
        .action(Arguments.storeFalse())
        .help(
            "Do not print tokens in NAF format, but conll tabulated format.\n");
  }

  /**
   * Main entry point for training.
   *
   * @throws IOException
   *           throws an exception if errors in the various file inputs.
   */
  public final void train() throws IOException {
	// load training parameters file
	String paramFile = parsedArguments.getString("params");
	TrainingParameters params = InputOutputUtils
	        .loadTrainingParameters(paramFile);
    String outModel = null;
    if (params.getSettings().get("OutputModel") == null || params.getSettings().get("OutputModel").length() == 0) {
        outModel = Files.getNameWithoutExtension(paramFile) + ".bin";
        params.put("OutputModel", outModel);
      }
      else {
        outModel = Flags.getModel(params);
      }
    Trainer posTaggerTrainer;
    if (Flags.getFeatureSet(params).equals("Opennlp")) {
      posTaggerTrainer = new DefaultTrainer(params);
    } else {
      posTaggerTrainer = new BaselineTrainer(params);
    }
    POSModel trainedModel = posTaggerTrainer.train(params);
    CmdLineUtil.writeModel("ixa-pipe-pos", new File(outModel), trainedModel);
  }

  /**
   * Loads the parameters for the training CLI.
   */
  public final void loadTrainingParameters() {
	  trainParser.addArgument("-p", "--params")
	   .required(true)
      .help("Load the training parameters file\n");
  }

  /**
   * Main entry point for evaluation.
   * @throws IOException the io exception thrown if
   * errors with paths are present
   */
  public final void eval() throws IOException {
    String outputFile = parsedArguments.getString("outputFile");
    String testFile = parsedArguments.getString("testSet");
    String model = parsedArguments.getString("model");
    int beam = parsedArguments.getInt("beamsize");

    Evaluate evaluator = new Evaluate(testFile, model, beam);
    if (parsedArguments.getString("evalReport") != null) {
      if (parsedArguments.getString("evalReport").equalsIgnoreCase("brief")) {
        evaluator.evaluate();
      } else if (parsedArguments.getString("evalReport").equalsIgnoreCase(
          "error")) {
        evaluator.evalError();
      } else if (parsedArguments.getString("evalReport").equalsIgnoreCase(
          "detailed")) {
        if (outputFile == null) {
          System.err.println("Specify a file to save the detailed report!!");
          System.exit(1);
        }
        evaluator.detailEvaluate(outputFile);
      }
    } else {
      if (outputFile == null) {
        System.err.println("Specify a file to save the detailed report!!");
        System.exit(1);
      }
      evaluator.detailEvaluate(outputFile);
    }
  }

  /**
   * Load the evaluation parameters of the CLI.
   */
  public final void loadEvalParameters() {
    evalParser.addArgument("-o", "--outputFile")
        .required(false)
        .help("Choose file to save detailed evalReport");
    evalParser.addArgument("-m", "--model")
         .required(true)
        .help("Choose model");
    evalParser.addArgument("-t", "--testSet")
        .required(true)
        .help("Input testset for evaluation");
    evalParser.addArgument("--evalReport")
        .required(false)
        .choices("brief", "detailed", "error")
        .help("Choose type of evaluation report; defaults to detailed");
    evalParser.addArgument("--beamsize")
        .setDefault(DEFAULT_BEAM_SIZE)
        .type(Integer.class)
        .help("Choose beam size for evaluation: 1 is faster.");
  }

}
