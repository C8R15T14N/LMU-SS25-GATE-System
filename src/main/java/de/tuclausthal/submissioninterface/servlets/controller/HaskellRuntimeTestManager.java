/*
 * Copyright 2025 Sven Strickroth <email@cs-ware.de>
 * Copyright 2025 Christian Wagner <christian.wagner@campus.lmu.de>
 *
 * This file is part of the GATE.
 *
 * GATE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * GATE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GATE. If not, see <http://www.gnu.org/licenses/>.
 */

package de.tuclausthal.submissioninterface.servlets.controller;

import static java.lang.Math.ceil;

import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tuclausthal.submissioninterface.persistence.dao.DAOFactory;
import de.tuclausthal.submissioninterface.persistence.dao.ParticipationDAOIf;
import de.tuclausthal.submissioninterface.persistence.dao.TestDAOIf;
import de.tuclausthal.submissioninterface.persistence.datamodel.DockerTestStep;
import de.tuclausthal.submissioninterface.persistence.datamodel.HaskellRuntimeTest;
import de.tuclausthal.submissioninterface.persistence.datamodel.Participation;
import de.tuclausthal.submissioninterface.persistence.datamodel.ParticipationRole;
import de.tuclausthal.submissioninterface.persistence.datamodel.Task;
import de.tuclausthal.submissioninterface.persistence.datamodel.Test;
import de.tuclausthal.submissioninterface.servlets.GATEController;
import de.tuclausthal.submissioninterface.servlets.RequestAdapter;
import de.tuclausthal.submissioninterface.servlets.view.MessageView;
import de.tuclausthal.submissioninterface.testframework.tests.impl.ProcessOutputGrabber;
import de.tuclausthal.submissioninterface.util.Configuration;
import de.tuclausthal.submissioninterface.util.TaskPath;
import de.tuclausthal.submissioninterface.util.Util;

/**
 * Controller-Servlet for clustering haskell submissions based on common errors (dynamic/runtime analysis).
 * This servlet allows advisors to automatically generate and modify test steps.
 *
 * @author Christian Wagner
 */
@GATEController
public class HaskellRuntimeTestManager extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	final static private Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	// TODO@CHW: should safe docker path be part of de.tuclausthal.submissioninterface.util.Configuration? (since it is duplicated from DockerTest)
	final static public String SAFE_DOCKER_SCRIPT = "/usr/local/bin/safe-docker";

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		getServletContext().getNamedDispatcher(DockerTestManager.class.getSimpleName()).forward(request, response);
	}

	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		Session session = RequestAdapter.getSession(request);
		TestDAOIf testDAOIf = DAOFactory.TestDAOIf(session);
		Test test = testDAOIf.getTest(Util.parseInteger(request.getParameter("testid"), 0));
		if (!(test instanceof HaskellRuntimeTest haskellRuntimeTest)) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			request.setAttribute("title", "Test nicht gefunden");
			getServletContext().getNamedDispatcher(MessageView.class.getSimpleName()).forward(request, response);
			return;
		}

		ParticipationDAOIf participationDAO = DAOFactory.ParticipationDAOIf(session);
		Participation participation = participationDAO.getParticipation(RequestAdapter.getUser(request), haskellRuntimeTest.getTask().getTaskGroup().getLecture());
		if (participation == null || participation.getRoleType() != ParticipationRole.ADVISOR) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "insufficient rights");
			return;
		}

		if ("getHaskellIdentifiers".equals(request.getParameter("action"))) {
			// TODO@CHW what happens if no model solution is uploaded?

			try {
				SubprocessResult res = evaluateWithGhci(new String[] { "hashable", "QuickCheck" }, new String[] { "Data.Hashable", "Test.QuickCheck" }, true, new String[] { ":browse", "hash [1,2,3]" }, test.getTask(), true);
				LOG.info("STDOUT IS {}", res.stdOut());
				LOG.info("STDERR IS {}", res.stdErr());
				LOG.info("EXIT CODE IS {}", res.exitCode());
				LOG.info("ABORTED IS {}", res.aborted());

				// TODO@CHW
				// browse haskell identifiers of model solution
				// start transaction to store identifiers in the database and commit
				// send redirect to HRTManager
				response.sendRedirect(Util.generateRedirectURL(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + haskellRuntimeTest.getId(), response));
			} catch (IOException e) {
				String errorMessage = URLEncoder.encode(Util.escapeHTML(e.getMessage()), StandardCharsets.UTF_8);
				response.sendRedirect(Util.generateRedirectURL(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + haskellRuntimeTest.getId() + "&getidentifiererror=" + errorMessage, response));
			}
		} else if ("generateNewTestSteps".equals(request.getParameter("action"))) {
			int numberOfTestSteps = Util.parseInteger(request.getParameter("numberOfTestSteps"), 0);

			List<DockerTestStepData> dockerTestStepDatas = generateTestcases(numberOfTestSteps, test.getTask());
			// TODO@CHW catch IOException

			Transaction tx = session.beginTransaction();
			for (DockerTestStepData dockerTestStepData : dockerTestStepDatas) {
				String title = dockerTestStepData.title();
				String testCode = dockerTestStepData.testCode().replaceAll("\r\n", "\n");
				String expectedValue = dockerTestStepData.expectedValue().replaceAll("\r\n", "\n");

				DockerTestStep newStep = new DockerTestStep(haskellRuntimeTest, title, testCode, expectedValue);
				session.persist(newStep);
			}

			tx.commit();

			response.sendRedirect(Util.generateRedirectURL(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + haskellRuntimeTest.getId(), response));
		} else {
			getServletContext().getNamedDispatcher(DockerTestManager.class.getSimpleName()).forward(request, response);
		}
	}

	private record DockerTestStepData(String title, String testCode, String expectedValue) {
	}


	private List<DockerTestStepData> generateTestcases(int numberOfTestSteps, Task task) throws IOException {
		List<DockerTestStepData> generatedTestcases = new ArrayList<>();

		List<String> haskellIdentifiers = browseModelSolution(task);
		
		LOG.info("ALL IDENTIFIERS:");
		for (String haskellIdentifier : haskellIdentifiers) {
			LOG.info(haskellIdentifier);
		}

		HaskellClassifiedIdentifiers haskellClassifiedIdentifiers = classifyHaskellIdentifiers(haskellIdentifiers);

		List<String> arbitraryInstances = new ArrayList<>();

		for(HaskellNewtypeOrData haskellNewtypeOrData : haskellClassifiedIdentifiers.getNewtypesAndDatas()) {
			String arbitraryInstance = haskellNewtypeOrData.generateArbitraryInstance();
			arbitraryInstances.add(arbitraryInstance);

			LOG.info("Newtype/data: {}", haskellNewtypeOrData.typename);
			LOG.info("Arbitrary instance: {}", arbitraryInstance);
		}

		for(HaskellFunction haskellFunction : haskellClassifiedIdentifiers.getFunctions()) {
			LOG.info("Function: {}", haskellFunction.name);
			LOG.info("Type:\t\t\t{}", haskellFunction.typeSignature);

			String defaultTypeSignature = getGhciDefaultTypeSignature(task, haskellFunction.name);
			LOG.info("Type (+d):\t\t{}", defaultTypeSignature);

			String concreteTypeSignature = replaceUnconstrainedTypeVariables(defaultTypeSignature, HaskellPrimitiveType.Int); // TODO@CHW other default type
			LOG.info("Concrete:\t\t{}", concreteTypeSignature);

			List<String> functionParameterTypes = getFunctionParameterTypes(concreteTypeSignature);
			LOG.info("Params:\t\t{}", functionParameterTypes);

			List<TestcaseWithTypes> testcases = generateQuickcheckFunctionTestcases(task, haskellFunction.name, functionParameterTypes, arbitraryInstances, numberOfTestSteps);

			List<String> functionCalls = generateFunctionCalls(haskellFunction.name, testcases);
			List<String> expectedValues = computeExpectedValues(functionCalls, task);

			if (functionCalls.size() != expectedValues.size()) {
				throw new AssertionError(String.format("Expected values: %d, function calls: %d", expectedValues.size(), functionCalls.size()));
			}

			for (int i = 0; i < functionCalls.size(); i++) {
				LOG.info(prettyPrintFunctionCall(functionCalls.get(i)));
				LOG.info(expectedValues.get(i));

				generatedTestcases.add(new DockerTestStepData("Testcase " + i, functionCalls.get(i), expectedValues.get(i)));
			}
		}

		return generatedTestcases;
	}

	private record SubprocessResult(String stdOut, String stdErr, int exitCode, boolean aborted) {
	}

	/** Evaluate haskell expressions using ghci expression evaluation mode (e.g. ghci -e "sum [1,2,3]").
	 *  Similar code in "de.tuclausthal.submissioninterface.testframework.tests.impl.DockerTest".
	 *  This code duplicate is justified for the following reasons:
	 *  1. The DockerTest is specifically designed for testing student submissions, and consists of a sequence
	 *     of DockerTestSteps that together evaluate a student submission. In contrast, this method evaluates
	 *     arbitrary ghci expressions that are used for generating testcases rather than for testing a submission.
	 *  2. The DockerTest includes logic to analyze the subprocess output, based on the DockerTestSteps it consists
	 *     of. Since this function evaluates arbitrary ghci expressions, this post-processing is not suitable here.
	 *
	 * @param packagesToEnable List of packages (e.g. ["QuickCheck"]), that need to be enabled (e.g. ":set -package QuickCheck")
	 * @param modulesToImport List of modules (e.g. ["Control.Monad", "Test.QuickCheck"]) that need to be imported (e.g. ":m + Control.Monad Test.QuickCheck")
	 * @param loadModelSolution Whether the model solution should be loaded into ghci (i.e. using "ghci :l")
	 * @param expressionsToEvaluate List of expressions (e.g. ["expr1", "expr2"]) that should be evaluated by ghci in this order (e.g. this will call "ghci -e expr1 -e expr2").
	 * @param task Task, for which the testcases should be generated based on the model solution
	 * @return result of the subprocess
	 */
	private SubprocessResult evaluateWithGhci(String[] packagesToEnable, String[] modulesToImport, boolean loadModelSolution, String[] expressionsToEvaluate, Task task, boolean throwIOExceptionOnNonZeroExitCode) throws IOException {
		if (packagesToEnable == null)
			packagesToEnable = new String[0];
		if (modulesToImport == null)
			modulesToImport = new String[0];
		if (expressionsToEvaluate == null)
			expressionsToEvaluate = new String[0];

		final Path taskPath = Util.constructPath(Configuration.getInstance().getDataPath(), task);
		final Path modelSolutionPath = taskPath.resolve(TaskPath.MODELSOLUTIONFILES.getPathComponent());
		final int safeDockerTimeout = 30;

		Path generatorTempDir = null;
		try {
			generatorTempDir = Util.createTemporaryDirectory("haskellruntimegenerator");
			if (generatorTempDir == null) {
				throw new IOException("Failed to create tempdir!");
			}

			final Path modelSolutionDir = generatorTempDir.resolve("modelsolution");
			Files.createDirectories(modelSolutionDir);

			final Path administrativeDir = generatorTempDir.resolve("administrative");
			Files.createDirectories(administrativeDir);

			if (Files.isDirectory(modelSolutionPath)) {
				Util.recursiveCopy(modelSolutionPath, modelSolutionDir);
			}

			Path hsFile = null;
			if (loadModelSolution) {
				// Expect exactly one .hs file among the modelsolution files -> this file will be used to generate the testcases
				try (Stream<Path> stream = Files.list(modelSolutionDir)) {
					List<Path> hsFiles = stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".hs")).toList();

					if (hsFiles.size() != 1) {
						throw new IOException("Expected exactly one .hs file in modelSolutionDir, found " + hsFiles.size() + " files.");
					} else {
						hsFile = hsFiles.get(0);
					}
				}
			}

			// TODO@CHW: testCode is more complex in DockerTest
			StringBuilder testCode = new StringBuilder("ghci -XInstanceSigs");
			for (String packageToEnable : packagesToEnable) {
				appendGhciEvaluateArgument(testCode, ":set -package " + packageToEnable);
			}
			appendGhciEvaluateArgument(testCode, ":m + " + String.join(" ", modulesToImport));
			if (hsFile != null) {
				appendGhciEvaluateArgument(testCode, ":load " + hsFile.getFileName().toString());
			}
			for (String expression : expressionsToEvaluate) {
				appendGhciEvaluateArgument(testCode, expression);
			}

			final Path testDriver = administrativeDir.resolve("test.sh");
			try (Writer fw = Files.newBufferedWriter(testDriver)) {
				fw.write(testCode.toString());
			}

			List<String> params = new ArrayList<>();
			params.add("sudo");
			params.add(SAFE_DOCKER_SCRIPT);
			params.add("--timeout=" + safeDockerTimeout);
			params.add("--dir=" + Util.escapeCommandlineArguments(administrativeDir.toAbsolutePath().toString()));
			params.add("--");
			params.add("bash");
			params.add(Util.escapeCommandlineArguments(testDriver.toAbsolutePath().toString()));

			ProcessBuilder pb = new ProcessBuilder(params);
			pb.directory(modelSolutionDir.toFile());

			// only forward explicitly specified environment variables to test processes
			pb.environment().keySet().removeIf(key -> !("PATH".equalsIgnoreCase(key) || "USER".equalsIgnoreCase(key) || "LANG".equalsIgnoreCase(key)));

			LOG.debug("Executing external process: {} in {}", params, modelSolutionDir);

			Process process = pb.start();
			ProcessOutputGrabber outputGrabber = new ProcessOutputGrabber(process);

			int exitCode = -1;

			boolean aborted = false;
			try {
				exitCode = process.waitFor();
			} catch (InterruptedException e) {
				aborted = true;
			}

			if (exitCode == 23 || exitCode == 24) { // magic value of the safe-docker script (23=timeout, 24=oom)
				aborted = true;
			}

			try {
				outputGrabber.waitFor();
			} catch (InterruptedException e) {
				throw new IOException("Running haskell testcase generator failed");
			}

			String stdOut = outputGrabber.getStdOutBuffer().toString();
			String stdErr = outputGrabber.getStdErrBuffer().toString();

			if (throwIOExceptionOnNonZeroExitCode) {
				if (exitCode == 23) {
					throw new IOException("Running haskell testcase generator timed out (Timeout: " + safeDockerTimeout + "s)");
				} else if (exitCode == 24) {
					throw new IOException("Running haskell testcase generator failed (Out of memory)");
				} else if (exitCode != 0) {
					throw new IOException("Running haskell testcase generator failed with exit code " + exitCode + ". Output on stderr: " + stdErr);
				}
			}

			return new SubprocessResult(stdOut, stdErr, exitCode, aborted);
		} finally {
			if (generatorTempDir != null) {
				Util.recursiveDelete(generatorTempDir);
			}
		}
	}

	private void appendGhciEvaluateArgument(StringBuilder testCode, String argument) {
		testCode.append(" -e '").append(argument.replace("'", "'\"'\"'").replace("\t", "    ")).append("'");
	}

	private List<String> browseModelSolution(Task task) throws IOException {
		SubprocessResult result = evaluateWithGhci(null, null, true, new String[] { ":browse" }, task, true);
		return splitLinesButKeepMultilines(result.stdOut());
	}

	public List<String> splitLinesButKeepMultilines(String resultStdout) {
		List<String> haskellIdentifiers = new ArrayList<>();

		for (String line : resultStdout.split("\\R")) {
			if (!(line.startsWith(" ") || line.startsWith("\t"))) {
				haskellIdentifiers.add(line);
			} else {
				// Handle multi-line formatting
				int lastIndex = haskellIdentifiers.size() - 1;
				if (lastIndex >= 0) {
					String updated = haskellIdentifiers.get(lastIndex) + "\n" + line;
					haskellIdentifiers.set(lastIndex, updated);
				}
			}
		}

		return haskellIdentifiers;
	}

	public HaskellClassifiedIdentifiers classifyHaskellIdentifiers(List<String> haskellIdentifiers) {
		HaskellClassifiedIdentifiers classifiedIdentifiers = new HaskellClassifiedIdentifiers();

		for (String line : haskellIdentifiers) {
			if (line.startsWith("class")) {
				classifiedIdentifiers.addClass(line);
			} else if (line.startsWith("newtype") || line.startsWith("data")) {
				classifiedIdentifiers.addNewtypeOrData(line);
			} else if (line.contains("::")) {
				classifiedIdentifiers.addFunction(line);
			} else if (!(line.isEmpty() || line.startsWith("type"))) {
				throw new IllegalArgumentException("Invalid Haskell identifier: " + line);
			}
		}

		return classifiedIdentifiers;
	}

	public String getGhciDefaultTypeSignature(Task task, String identifierName) throws IOException {
		SubprocessResult result = evaluateWithGhci(null, null, true, new String[] { ":type +d " + identifierName }, task, true);

		String defaultTypeSignature = normalizeTypeSignature(result.stdOut().split("::")[1].trim());
		if (defaultTypeSignature.contains("=>")) {
			throw new IllegalArgumentException("Constraint => after :type +d not yet handled"); // TODO@CHW
		} else {
			return defaultTypeSignature;
		}
	}

	public String normalizeTypeSignature(String typeSignature) {
		typeSignature = typeSignature.replace("\n", "");
		typeSignature = typeSignature.replaceAll("\\s*->\\s*", " -> ");
		return typeSignature.trim();
	}

	public static String replaceUnconstrainedTypeVariables(String typeSignature, HaskellPrimitiveType replacementType) {
		if (typeSignature.contains("=>")) {
			// TODO@CHW: Implementation not yet correct for Functor, Monad, Applicative, etc.
			// e.g. (<$>) :: Functor f => (a -> b) -> f a -> f b
			//      (=<<) :: Monad m => (a -> m b) -> m a -> m b
			throw new IllegalArgumentException("Type signature contains constraints, not implemented yet");
		}

		if (typeSignature.contains("::")) {
			typeSignature = typeSignature.split("::", 2)[1].trim();
		}

		return typeSignature.replaceAll("(?<![a-zA-Z])([a-z][a-zA-Z0-9]*)", replacementType.toString());
	}

	public static List<String> getFunctionParameterTypes(String concreteTypeSignature) {
		if (concreteTypeSignature.contains("::")) {
			concreteTypeSignature = concreteTypeSignature.split("::", 2)[1].trim();
		}
		if (concreteTypeSignature.contains("=>")) {
			throw new IllegalArgumentException("Function type signature should only contain concrete types.");
		}

		List<String> parts = splitExceptBetweenParentheses(concreteTypeSignature, '(', ')', "->");

		return parts.subList(0, parts.size() - 1); // remove return type (last element)
	}

	//TODO@CHW maybe replace all trim() by strip()?

	public static boolean parameterTypeIsFunction(String parameterType) {
		parameterType = parameterType.strip();
		return parameterType.contains("->") && parameterType.startsWith("(") && parameterType.endsWith(")");
	}

	public static String getFunctionReturnType(String concreteTypeSignature) {
		if (concreteTypeSignature.contains("::")) {
			concreteTypeSignature = concreteTypeSignature.split("::", 2)[1].strip();
		}
		if (concreteTypeSignature.contains("=>")) {
			throw new IllegalArgumentException("Function type signature should only contain concrete types.");
		}

		List<String> parts = splitExceptBetweenParentheses(concreteTypeSignature, '(', ')', "->");

		// TODO@CHW: this can throw index out of bounds exception?
		return parts.get(parts.size() - 1); // return type (last element)
	}

	private record TestcaseSingleParameterWithType(String testcaseParameter, String type) {
	}

	private record TestcaseWithTypes(List<TestcaseSingleParameterWithType> testcaseParametersWithTypes) {
	}

	private List<TestcaseWithTypes> generateQuickcheckFunctionTestcases(Task task, String functionName, List<String> functionParameterTypes, List<String> arbitraryInstances, int numberOfTestcases) throws IOException {
		if (functionParameterTypes.isEmpty()) {
			return List.of();
		}

		final String TESTCASE_SEPARATOR = "@NEXT-TESTCASE@"; // TODO@CHW: use random value in all separators
		final String TESTCASE_VALUE_SEPARATOR = "@NEXT-TESTCASE-VALUE@";

		/*
		 * Placeholder type avoids that ghci simplifies tuples. Example:
		 * - Gen ((Int, Int)) is automatically simplified to Gen (Int, Int)
		 * - => A single parameter of type (Int, Int) is considered as two parameters of type Int
		 * - Gen (PlaceholderT, (Int, Int)) is NOT simplified to Gen (PlaceholderT, Int, Int)
		 */
		final String PLACEHOLDER_TYPE_NAME = "Placeholder";
		final String CYCLIC_INT_MAP_TYPE_NAME = "CyclicIntMap";

		List<String> quickcheckParameterTypes = withCyclicIntMapTypes(withConstrainedPrimitiveTypes(functionParameterTypes), CYCLIC_INT_MAP_TYPE_NAME);

		List<String> parameterTypesTupleValues = new ArrayList<>();
		parameterTypesTupleValues.add(PLACEHOLDER_TYPE_NAME);
		parameterTypesTupleValues.addAll(quickcheckParameterTypes);

		// In ein Tuple-String umwandeln
		String parameterTypeTuple = "(" + String.join(", ", parameterTypesTupleValues) + ")";

		LOG.info("- PARAMS TUPLE:\t\t" + parameterTypeTuple);

		String placeholderType = String.format("""
				data %1$s = %1$s
				
				instance Show %1$s where
				  show %1$s = "@PLACEHOLDER@"
				
				instance Arbitrary %1$s where
				  arbitrary = return %1$s
				""", PLACEHOLDER_TYPE_NAME);

		String cyclicIntMap = String.format("""
				data %1$s target = %1$s {
				  name :: String,
				  cycleLength :: Int,
				  intMap :: Int -> target
				}
				
				instance Show target => Show (%1$s target) where
				  show (%1$s name cycleLength intMap) =
				    name
				      ++ " i = case i of "
				      ++ concatMap (liftM2 (++) show ((" -> " ++) . (++ "; ") . show . intMap)) [0 .. cycleLength - 1]
				      ++ "x -> "
				      ++ name
				      ++ " (abs (mod x "
				      ++ show cycleLength
				      ++ "))"
				
				instance Arbitrary target => Arbitrary (%1$s target) where
				  arbitrary = %1$s "cyclicIntMap" 50 <$> arbitrary
				""", CYCLIC_INT_MAP_TYPE_NAME);

		// String safeAsciiValues = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz()[]{}+-*/.,:; _!?#$%&<=>@";
		String safeAsciiValues = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"; // TODO@CHW
		String typenameCharOnlySafeAscii = HaskellConstrainedPrimitiveType.Char_OnlySafeAscii.toString();
		String typenameStringOnlySafeAscii = HaskellConstrainedPrimitiveType.String_OnlySafeAscii.toString();

		String charStringOnlySafeAscii = String.format("""
				safeAsciiChar :: Gen Char
				safeAsciiChar = elements "%1$s"
				
				newtype %2$s = %2$s Char
				
				instance Show %2$s where
				  show (%2$s c) = show c
				
				instance Arbitrary %2$s where
				  arbitrary = %2$s <$> safeAsciiChar
				
				newtype %3$s = %3$s String
				
				instance Show %3$s where
				  show (%3$s s) = show s
				
				instance Arbitrary %3$s where
				  arbitrary = %3$s <$> listOf safeAsciiChar
				""", safeAsciiValues, typenameCharOnlySafeAscii, typenameStringOnlySafeAscii);

		String toStringList = String.format("""
				class ToStringList a where toStringList :: a -> [String]
				
				instance (Show a, Show b) => ToStringList (a, b) where toStringList (a, b) = [show a, show b]
				
				instance (Show a, Show b, Show c) => ToStringList (a, b, c) where
				  toStringList (a, b, c) = [show a, show b, show c]
				
				instance (Show a, Show b, Show c, Show d) => ToStringList (a, b, c, d) where
				  toStringList (a, b, c, d) = [show a, show b, show c, show d]
				
				instance (Show a, Show b, Show c, Show d, Show e) => ToStringList (a, b, c, d, e) where
				  toStringList (a, b, c, d, e) = [show a, show b, show c, show d, show e]
				
				instance (Show a, Show b, Show c, Show d, Show e, Show f) => ToStringList (a, b, c, d, e, f) where
				  toStringList (a, b, c, d, e, f) = [show a, show b, show c, show d, show e, show f]
				
				instance (Show a, Show b, Show c, Show d, Show e, Show f, Show g) => ToStringList (a, b, c, d, e, f, g) where
				  toStringList (a, b, c, d, e, f, g) = [show a, show b, show c, show d, show e, show f, show g]
				
				instance (Show a, Show b, Show c, Show d, Show e, Show f, Show g, Show h) => ToStringList (a, b, c, d, e, f, g, h) where
				  toStringList (a, b, c, d, e, f, g, h) = [show a, show b, show c, show d, show e, show f, show g, show h]
				
				instance (Show a, Show b, Show c, Show d, Show e, Show f, Show g, Show h, Show i) => ToStringList (a, b, c, d, e, f, g, h, i) where
				  toStringList (a, b, c, d, e, f, g, h, i) = [show a, show b, show c, show d, show e, show f, show g, show h, show i]
				
				instance (Show a, Show b, Show c, Show d, Show e, Show f, Show g, Show h, Show i, Show j) => ToStringList (a, b, c, d, e, f, g, h, i, j) where
				  toStringList (a, b, c, d, e, f, g, h, i, j) = [show a, show b, show c, show d, show e, show f, show g, show h, show i, show j]
				""");

		String haskellCommand = String.format("""
				replicateM %d (generate (arbitrary :: Gen %s))
				  >>= putStrLn . intercalate testcaseSeparator . map
				        ( intercalate testcaseValueSeparator
				        . filter (/= show %s)
				        . toStringList
				        )
				""", numberOfTestcases, parameterTypeTuple, PLACEHOLDER_TYPE_NAME);

		List<String> expressionsToEvaluate = new ArrayList<>();
		expressionsToEvaluate.add("testcaseSeparator = \"" + TESTCASE_SEPARATOR + "\"");
		expressionsToEvaluate.add("testcaseValueSeparator = \"" + TESTCASE_VALUE_SEPARATOR + "\"");
		expressionsToEvaluate.add(placeholderType);
		expressionsToEvaluate.add(charStringOnlySafeAscii);
		expressionsToEvaluate.add(toStringList);
		expressionsToEvaluate.add(cyclicIntMap);
		expressionsToEvaluate.add(String.join("\n", arbitraryInstances));
		expressionsToEvaluate.add(haskellCommand);

		final String[] packagesToEnable = new String[] { "QuickCheck" };
		final String[] modulesToImport = new String[] { "Control.Monad", "Test.QuickCheck", "Data.List" };
		SubprocessResult result = evaluateWithGhci(packagesToEnable, modulesToImport, true, expressionsToEvaluate.toArray(new String[0]), task, true);

		String rawOutput = result.stdOut();
		if (rawOutput.endsWith("\n")) {
			rawOutput = rawOutput.substring(0, rawOutput.length() - 1);
		}

		List<List<String>> testcases = Arrays.stream(rawOutput.split(TESTCASE_SEPARATOR)).map(testcase -> Arrays.asList(testcase.split(TESTCASE_VALUE_SEPARATOR))).toList();

		List<TestcaseWithTypes> testcasesWithTypes = new ArrayList<>();

		for (List<String> testcase : testcases) {
			if (testcase.size() != functionParameterTypes.size()) {
				throw new AssertionError("Testcase length does not match function parameter types length");
			}

			List<TestcaseSingleParameterWithType> testcaseWithType = new ArrayList<>();

			for (int i = 0; i < testcase.size(); i++) {
				testcaseWithType.add(new TestcaseSingleParameterWithType(testcase.get(i), functionParameterTypes.get(i)));
			}

			for (int i = 0; i < testcaseWithType.size(); i++) {
				TestcaseSingleParameterWithType current = testcaseWithType.get(i);
				String testcaseValue = current.testcaseParameter();
				String testcaseValueType = current.type();

				if (parameterTypeIsFunction(testcaseValueType)) {
					// remove outer parentheses
					String innerType = testcaseValueType.strip();
					innerType = innerType.substring(1, innerType.length() - 1).strip();

					int numberOfParameters = getFunctionParameterTypes(innerType).size();

					String newTestcaseValue = embedCyclicIntMapInRandomFunction(numberOfParameters, testcaseValue);
					testcaseWithType.set(i, new TestcaseSingleParameterWithType(newTestcaseValue, testcaseValueType));
				}
			}
			testcasesWithTypes.add(new TestcaseWithTypes(testcaseWithType));
		}

		return testcasesWithTypes;
	}

	private List<String> withConstrainedPrimitiveTypes(List<String> parameterTypes) {
		Map<String, String> replacementDict = Map.of(HaskellPrimitiveType.Char.toString(), HaskellConstrainedPrimitiveType.Char_OnlySafeAscii.toString(), HaskellPrimitiveType.String.toString(), HaskellConstrainedPrimitiveType.String_OnlySafeAscii.toString());

		String patternString = String.join("|", replacementDict.keySet().stream().map(key -> "(?<![A-Za-z])" + Pattern.quote(key) + "(?![A-Za-z0-9_])").toList());
		Pattern pattern = Pattern.compile(patternString);

		List<String> constrainedTypes = new ArrayList<>();

		for (String parameterType : parameterTypes) {
			Matcher matcher = pattern.matcher(parameterType);
			StringBuilder sb = new StringBuilder();

			// TODO@CHW: not verified this while but looks fine
			while (matcher.find()) {
				String matchedKey = matcher.group();
				String replacement = replacementDict.get(matchedKey);
				matcher.appendReplacement(sb, replacement);
			}
			matcher.appendTail(sb);

			constrainedTypes.add(sb.toString());
		}

		LOG.info("- PARAMS (constr.):\t" + constrainedTypes);
		return constrainedTypes;
	}

	private List<String> withCyclicIntMapTypes(List<String> parameterTypes, String cyclicIntMapConstructor) {
		List<String> cyclicIntMapTypes = new ArrayList<>();

		for (String parameterType : parameterTypes) {
			if (parameterTypeIsFunction(parameterType)) {
				parameterType = parameterType.strip().substring(1, parameterType.length() - 1).strip();

				String returnType = getFunctionReturnType(parameterType);
				cyclicIntMapTypes.add(cyclicIntMapConstructor + " " + returnType);
			} else {
				cyclicIntMapTypes.add(parameterType);
			}
		}

		return cyclicIntMapTypes;
	}

	private String embedCyclicIntMapInRandomFunction(int numberOfParameters, String cyclicIntMapDefinition) {
		String cyclicIntMapName = cyclicIntMapDefinition.split("\\s+")[0].trim();

		List<String> parameters = new ArrayList<>();
		for (int i = 0; i < numberOfParameters; i++) {
			parameters.add("p" + i);
		}

		return String.format("(let %s in let randomFunction %s = (%s . hash . show) (%s) in randomFunction)", cyclicIntMapDefinition, String.join(" ", parameters), cyclicIntMapName, String.join(", ", parameters));
	}

	public List<String> generateFunctionCalls(String functionName, List<TestcaseWithTypes> testcasesWithTypes) {
		List<String> functionCalls = new ArrayList<>();

		for (TestcaseWithTypes testcaseWithTypes : testcasesWithTypes) {
			// testcaseWithTypes contains for example: [('9', 'Int'), ('Just 18', 'Maybe Int')]
			List<String> parts = new ArrayList<>();
			parts.add(functionName);

			for (TestcaseSingleParameterWithType testcaseSingleParameterWithType : testcaseWithTypes.testcaseParametersWithTypes()) {
				String value = testcaseSingleParameterWithType.testcaseParameter();
				String hsType = testcaseSingleParameterWithType.type();
				parts.add(String.format("(%s :: %s)", value, hsType));
			}

			String functionCall = String.join(" ", parts);
			functionCalls.add(functionCall);
		}

		return functionCalls;
	}

	// TODO@CHW return value was list[str|None] in python, double check that this is fine
	public List<String> computeExpectedValues(List<String> functionCalls, Task task) throws IOException {
		final String exceptionLinePrefix = "@EXCEPTION@";

		List<String> wrappedFunctionCalls = functionCalls.stream().map(functionCall -> wrapGhciExpressionInCatch(functionCall, exceptionLinePrefix)).toList();

		String expectedValueSeparator = "@NEXT-EXPECTED-VALUE@";

		List<String> expressionsToEvaluate = new ArrayList<>();
		for (String wrappedFunctionCall : wrappedFunctionCalls) {
			expressionsToEvaluate.add(wrappedFunctionCall);
			expressionsToEvaluate.add(String.format("putStr \"%s\"", expectedValueSeparator));
		}

		String[] packagesToEnable = new String[] { "hashable" };
		String[] modulesToImport = new String[] { "Control.Exception Data.Hashable" };
		SubprocessResult result = evaluateWithGhci(packagesToEnable, modulesToImport, true, expressionsToEvaluate.toArray(new String[0]), task, true);

		List<String> expectedValues = new ArrayList<>();

		for (String outputValue : result.stdOut().split(expectedValueSeparator)) {
			if (!outputValue.trim().isEmpty()) {
				expectedValues.add(outputValue.startsWith(exceptionLinePrefix) ? null : outputValue);
			}
		}

		if (expectedValues.size() != wrappedFunctionCalls.size()) {
			throw new AssertionError(String.format("Expected values: %d, function calls: %d", expectedValues.size(), wrappedFunctionCalls.size()));
		}

		return expectedValues;
	}

	public String wrapGhciExpressionInCatch(String expression, String exceptionLinePrefix) {
		return String.format("catch (putStr (show (%s))) ((putStr . (\"%s\" ++) . show) :: SomeException -> IO ())", expression, exceptionLinePrefix);
	}

	public static String prettyPrintFunctionCall(String functionCall) {
		return functionCall.replaceAll("\\(let cyclicIntMap .*? let randomFunction .*? in randomFunction\\)", "<random function>");
	}

	// BEGIN OF HASKELL UTILS / UTILS --------------------------------------------------------

	private class HaskellClassifiedIdentifiers {
		private final List<HaskellClass> classes = new ArrayList<>();
		private final List<HaskellNewtypeOrData> newtypesAndDatas = new ArrayList<>();
		private final List<HaskellFunction> functions = new ArrayList<>();

		public void addClass(String hsClass) {
			classes.add(new HaskellClass(hsClass));
		}

		public void addNewtypeOrData(String hsNewtypeOrData) {
			newtypesAndDatas.add(new HaskellNewtypeOrData(hsNewtypeOrData));
		}

		public void addFunction(String hsFunction) {
			functions.add(new HaskellFunction(hsFunction));
		}

		public List<HaskellClass> getClasses() {
			return classes;
		}

		public List<HaskellNewtypeOrData> getNewtypesAndDatas() {
			return newtypesAndDatas;
		}

		public List<HaskellFunction> getFunctions() {
			return functions;
		}
	}

	private class HaskellClass {
		private final String hsClass;

		public HaskellClass(String hsClass) {
			if (!hsClass.startsWith("class") || !hsClass.contains("where")) {
				throw new IllegalArgumentException("Invalid class definition: " + hsClass);
			}
			this.hsClass = hsClass;
		}

		public String getHsClass() {
			return hsClass;
		}
	}

	private class HaskellFunction {
		private final String name;
		private final String typeSignature;

		public HaskellFunction(String hsFunction) {
			if (!hsFunction.contains("::")) {
				throw new IllegalArgumentException("Invalid function definition: " + hsFunction);
			}

			String[] parts = hsFunction.split("::", 2);
			this.name = parts[0].trim();
			this.typeSignature = parts[1].trim();
		}

		public String getName() {
			return name;
		}

		public String getTypeSignature() {
			return typeSignature;
		}

		@Override
		public String toString() {
			return "FUNCTION " + name + " :: " + typeSignature;
		}
	}

	private class HaskellNewtypeOrData {
		private final String typename;
		private final List<String> constructors = new ArrayList<>();

		public HaskellNewtypeOrData(String hsNewtypeOrData) {
			String normalizedInput = hsNewtypeOrData.replace("\n", " ").trim();

			Pattern typePattern = Pattern.compile("\\b(?:data|newtype)\\s+" + "(?<typename>[\\w\\s]+?)" + "\\s*=\\s*" + "(?<constructors>.*?)(?=\\s+deriving\\b|$)");

			Matcher matcher = typePattern.matcher(normalizedInput);
			if (matcher.find()) {
				this.typename = matcher.group("typename").trim().replace("\n", " ");

				Pattern namedConstructorPattern = Pattern.compile("(?<constr>\\b\\w+\\b)\\s*\\{\\s*(?<fields>.*?)\\s*}");

				for (String constructor : matcher.group("constructors").replace("\n", " ").trim().split("\\|")) {
					Matcher namedConstructorPatternMatch = namedConstructorPattern.matcher(constructor.trim());

					if (namedConstructorPatternMatch.find()) {
						String constr = namedConstructorPatternMatch.group("constr");
						String fields = namedConstructorPatternMatch.group("fields");
						List<String> fieldTypes = new ArrayList<>();

						for (String f : fields.split(",")) {
							String[] parts = f.split("::");
							if (parts.length == 2) {
								fieldTypes.add(parts[1].trim());
							}
						}
						constructors.add(constr + " " + String.join(" ", fieldTypes));
					} else {
						constructors.add(constructor.trim());
					}
				}
			} else {
				throw new IllegalArgumentException("Invalid newtype/data definition: " + hsNewtypeOrData);
			}
		}

		@Override
		public String toString() {
			return "NEWTYPE/DATA " + typename + " = " + constructors;
		}

		public String generateArbitraryInstance() {
			List<String> recursiveReturnExpressions = new ArrayList<>();
			List<String> nonrecursiveReturnExpressions = new ArrayList<>();

			for (String constructor : constructors) {
				List<String> parts = splitExceptBetweenParentheses(constructor.trim(), '(', ')', " "); // TOOD@CHW this line is not yet correct
				String constructorName = constructor.trim().split("\\s+")[0];
				List<String> constructorArgs = parts.subList(1, parts.size());

				StringBuilder returnExpression = new StringBuilder("return " + constructorName);

				int totalNumRecursiveCalls = 0;
				for (String arg : constructorArgs) {
					if (constructorArgIsRecursive(arg)) {
						totalNumRecursiveCalls++;
					}
				}

				int recursiveCallId = 0;
				for (String constructorArg : constructorArgs) {
					if (constructorArgIsRecursive(constructorArg)) {
						returnExpression.append(" <*> _gen (splitElements (n - 1) ").append(totalNumRecursiveCalls).append(" ").append(recursiveCallId).append(")");
						recursiveCallId++;
					} else {
						returnExpression.append(" <*> arbitrary");
					}
				}

				if (recursiveCallId > 0) {
					recursiveReturnExpressions.add(returnExpression.toString());
				} else {
					nonrecursiveReturnExpressions.add(returnExpression.toString());
				}
			}

			String[] typenameWithTypeVariables = typename.split(" ");
			List<String> typeVariables = Arrays.asList(typenameWithTypeVariables).subList(1, typenameWithTypeVariables.length);
			String constraint = typeVariables.isEmpty() ? "" : "(" + String.join(", ", typeVariables.stream().map(v -> "Arbitrary " + v).toList()) + ") => ";

			List<String> freqTuples = getFreqTuples(recursiveReturnExpressions, nonrecursiveReturnExpressions);

			// TODO@CHW: is the indentation correct?
			return String.format("""
					instance %sArbitrary (%s) where
					  arbitrary = sized _gen
					    where
					      _gen n
					        | n > 10 = _gen 10
					        | n > 0 && %s = frequency [%s]
					        | otherwise = oneof [%s]
					        where
					          splitElements numElements numRecursiveCalls recursiveCallId =
					            div numElements numRecursiveCalls + intDivRoundingCompensation
					              where
					                intDivRoundingCompensation =
					                  if recursiveCallId < mod numElements numRecursiveCalls then 1 else 0
					""", constraint, typename, recursiveReturnExpressions.isEmpty() ? "False" : "True", String.join(", ", freqTuples), String.join(", ", nonrecursiveReturnExpressions));
		}

		private static List<String> getFreqTuples(List<String> recursiveReturnExpressions, List<String> nonrecursiveReturnExpressions) {
			int recursiveProb = recursiveReturnExpressions.isEmpty() ? 0 : (int) ceil(82.0 / recursiveReturnExpressions.size());
			int nonrecursiveProb = nonrecursiveReturnExpressions.isEmpty() ? 0 : (int) ceil(18.0 / nonrecursiveReturnExpressions.size());

			List<String> freqTuples = new ArrayList<>();
			for (String r : recursiveReturnExpressions)
				freqTuples.add("(" + recursiveProb + ", " + r + ")");
			for (String n : nonrecursiveReturnExpressions)
				freqTuples.add("(" + nonrecursiveProb + ", " + n + ")");
			return freqTuples;
		}

		private boolean constructorArgIsRecursive(String arg) {
			arg = arg.trim();
			if (arg.startsWith("(") && arg.endsWith(")")) {
				return arg.equals("(" + this.typename + ")");
			} else {
				return arg.equals(this.typename);
			}
		}
	}

	public enum HaskellPrimitiveType {
		Integer, Int, Float, Double, Rational, Bool, Char, String
	}

	public enum HaskellConstrainedPrimitiveType {
		Char_OnlySafeAscii, String_OnlySafeAscii
		// TODO@CHW more ideas: Int_OnlyPositive, Float_OnlyPositive, Double_OnlyPositive
	}

	// TODO@CHW check all visibilities (public/private) in this file

	public static List<String> splitExceptBetweenParentheses(String expression, char openingParenthesis, char closingParenthesis, String splitAt) {
		List<String> tokens = new ArrayList<>();
		int parenthesisDepth = 0;
		StringBuilder currentToken = new StringBuilder();

		int index = 0;
		while (index < expression.length()) {
			char ch = expression.charAt(index);

			if (ch == openingParenthesis) {
				parenthesisDepth++;
				currentToken.append(ch);
			} else if (ch == closingParenthesis) {
				parenthesisDepth--;
				currentToken.append(ch);
			} else if (expression.startsWith(splitAt, index) && parenthesisDepth == 0) {
				tokens.add(currentToken.toString().trim());
				currentToken.setLength(0);
				index += splitAt.length() - 1; // skip already-processed characters
			} else {
				currentToken.append(ch);
			}
			index++;
		}

		tokens.add(currentToken.toString().trim());
		return tokens;
	}

	// END OF HASKELL UTILS / UTILS -----------------------------------------------------------------------------------

}

