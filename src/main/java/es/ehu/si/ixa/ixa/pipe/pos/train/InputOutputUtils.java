package es.ehu.si.ixa.ixa.pipe.pos.train;

/*
 *Copyright 2013 Rodrigo Agerri

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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import opennlp.tools.cmdline.TerminateToolException;
import opennlp.tools.ml.TrainerFactory;
import opennlp.tools.util.InputStreamFactory;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;
import opennlp.tools.util.model.BaseModel;

import org.apache.commons.io.FileUtils;

/**
 * Utility functions to read and save ObjectStreams.
 * @author ragerri
 * @version 2014-07-08
 *
 */
public final class InputOutputUtils {

  /**
   * Private constructor. This class should only be used statically.
   */
  private InputOutputUtils() {

  }

  /**
   * Check input file integrity.
   * @param name
   *          the name of the file
   * @param inFile
   *          the file
   */
  private static void checkInputFile(final String name, final File inFile) {

    String isFailure = null;

    if (inFile.isDirectory()) {
      isFailure = "The " + name + " file is a directory!";
    } else if (!inFile.exists()) {
      isFailure = "The " + name + " file does not exist!";
    } else if (!inFile.canRead()) {
      isFailure = "No permissions to read the " + name + " file!";
    }

    if (null != isFailure) {
      throw new TerminateToolException(-1, isFailure + " Path: "
          + inFile.getAbsolutePath());
    }
  }

  /**
   * Load the parameters in the {@code TrainingParameters} file.
   *
   * @param paramFile
   *          the training parameters file
   * @return default loading of the parameters
   */
  public static TrainingParameters loadTrainingParameters(final String paramFile) {
    return loadTrainingParameters(paramFile, false);
  }

  /**
   * Load the parameters in the {@code TrainingParameters} file.
   *
   * @param paramFile
   *          the parameter file
   * @param supportSequenceTraining
   *          wheter sequence training is supported
   * @return the parameters
   */
  private static TrainingParameters loadTrainingParameters(
      final String paramFile, final boolean supportSequenceTraining) {

    TrainingParameters params = null;

    if (paramFile != null) {

      checkInputFile("Training Parameter", new File(paramFile));

      InputStream paramsIn = null;
      try {
        paramsIn = new FileInputStream(new File(paramFile));

        params = new opennlp.tools.util.TrainingParameters(paramsIn);
      } catch (IOException e) {
        throw new TerminateToolException(-1,
            "Error during parameters loading: " + e.getMessage(), e);
      } finally {
        try {
          if (paramsIn != null) {
            paramsIn.close();
          }
        } catch (IOException e) {
          System.err.println("Error closing the input stream");
        }
      }

      if (!TrainerFactory.isValid(params.getSettings())) {
        throw new TerminateToolException(1, "Training parameters file '"
            + paramFile + "' is invalid!");
      }
    }

    return params;
  }

  /**
   * Read the file into an {@code ObjectStream}.
   *
   * @param infile
   *          the string pointing to the file
   * @return the object stream
   * @throws IOException
   *           throw exception if error occurs
   */
  public static ObjectStream<String> readInputData(final String infile)
      throws IOException {

    InputStreamFactory inputStreamFactory = new DefaultInputStreamFactory(
        new FileInputStream(infile));
    ObjectStream<String> lineStream = new PlainTextByLineStream(
        inputStreamFactory, "UTF-8");
    return lineStream;

  }

  /**
   * Print the results of each iteration in the cross evaluation training.
   *
   * @param results
   *          the results
   * @throws IOException
   *           io exception
   */
  public static void printIterationResults(
      final Map<List<Integer>, Double> results) throws IOException {
    for (Map.Entry<List<Integer>, Double> result : results.entrySet()) {
      Double value = result.getValue();
      List<Integer> key = result.getKey();
      System.out.print("Parameters: ");
      for (Integer s : key) {
        System.out.print(s + " ");
      }
      System.out.println("Value: " + value);
    }
  }

  /**
   * Keep the best iteration in the cross evaluation process.
   *
   * @param results
   *          the results
   * @param allParams
   *          the parameters
   * @return the best parameters
   * @throws IOException
   *           io exception
   */
  public static List<List<Integer>> getBestIterations(
      final Map<List<Integer>, Double> results,
      final List<List<Integer>> allParams) throws IOException {
    StringBuffer sb = new StringBuffer();
    Double bestResult = (Collections.max(results.values()));
    for (Map.Entry<List<Integer>, Double> result1 : results.entrySet()) {
      if (result1.getValue().compareTo(bestResult) == 0) {
        allParams.add(result1.getKey());
        sb.append("Best results: ").append(result1.getKey()).append(" ")
            .append(result1.getValue()).append("\n");
        System.out.println("Results: " + result1.getKey() + " "
            + result1.getValue());
      }
    }
    FileUtils.writeStringToFile(new File("best-results.txt"), sb.toString(),
        "UTF-8");
    System.out.println("Best F via cross evaluation: " + bestResult);
    System.out.println("All Params " + allParams.size());
    return allParams;
  }

  /**
   * Save the model.
   *
   * @param trainedModel
   *          the {@code BaseModel} trained.
   * @param outfile
   *          the outfile string
   */
  public static void saveModel(final BaseModel trainedModel,
      final String outfile) {
    OutputStream modelOut = null;
    try {
      modelOut = new BufferedOutputStream(new FileOutputStream(outfile));
      trainedModel.serialize(modelOut);
    } catch (IOException e) {
      // Failed to save model
      e.printStackTrace();
    } finally {
      if (modelOut != null) {
        try {
          modelOut.close();
        } catch (IOException e) {
          // Failed to correctly save model.
          // Written model might be invalid.
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * Print an exception if not development set is provided for cross evaluation.
   */
  public static void devSetException() {
    System.err.println("Use --devSet option if performing crossEvaluation!");
    System.out
        .println("Run java -jar target/ixa-pipe-train-1.0.jar -help "
    + " for details; also check your trainParams.txt file");
    System.exit(1);
  }

}
