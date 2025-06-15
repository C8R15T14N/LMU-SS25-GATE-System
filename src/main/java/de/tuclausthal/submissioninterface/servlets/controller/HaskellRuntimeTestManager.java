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

import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

			SubprocessResult res = evaluateWithGhci(new String[] { ":set -package hashable", ":m + Data.Hashable", "hash [1,2,3]" }, test.getTask());

			LOG.info("STDOUT IS {}", res.stdOut());
			LOG.info("STDERR IS {}", res.stdErr());
			LOG.info("EXIT CODE IS {}", res.exitCode());
			LOG.info("ABORTED IS {}", res.aborted());

			// TODO@CHW
			// browse haskell identifiers of model solution
			// start transaction to store identifiers in the database and commit
			// send redirect to HRTManager
			response.sendRedirect(Util.generateRedirectURL(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + haskellRuntimeTest.getId(), response));
		} else if ("generateNewTestSteps".equals(request.getParameter("action"))) {
			int numberOfTestSteps = Util.parseInteger(request.getParameter("numberOfTestSteps"), 0);
			String[][] testcases = new String[numberOfTestSteps][3];

			// TODO@CHW: this is just a placeholder for the actual testcase generator
			for (int testStepId = 0; testStepId < numberOfTestSteps; testStepId++) {
				testcases[testStepId][0] = "Testcase " + testStepId;
				testcases[testStepId][1] = "ghci -e " + testStepId + "+" + testStepId;
				testcases[testStepId][2] = "" + (testStepId + testStepId);
			}

			Transaction tx = session.beginTransaction();
			for (int testStepId = 0; testStepId < numberOfTestSteps; testStepId++) {
				String title = testcases[testStepId][0];
				String testCode = testcases[testStepId][1].replaceAll("\r\n", "\n");
				String expect = testcases[testStepId][2].replaceAll("\r\n", "\n");

				DockerTestStep newStep = new DockerTestStep(haskellRuntimeTest, title, testCode, expect);
				session.persist(newStep);
			}
			tx.commit();

			response.sendRedirect(Util.generateRedirectURL(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + haskellRuntimeTest.getId(), response));
		} else {
			getServletContext().getNamedDispatcher(DockerTestManager.class.getSimpleName()).forward(request, response);
		}
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
	 * @param expressions List of expressions (e.g. ["expr1", "expr2"]) that should be evaluated by ghci in this order
	 *                    (e.g. this will call "ghci -e expr1 -e expr2").
	 * @param task Task, for which the testcases should be generated based on the model solution
	 * @return result of the subprocess
	 */
	private SubprocessResult evaluateWithGhci(String[] expressions, Task task) throws IOException {
		final Path taskPath = Util.constructPath(Configuration.getInstance().getDataPath(), task);
		final Path modelSolutionPath = taskPath.resolve(TaskPath.MODELSOLUTIONFILES.getPathComponent());
		final int safeDockerTimeout = 10;

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

			// TODO@CHW: testCode is more complex in DockerTest
			StringBuilder testCode = new StringBuilder("ghci");
			for (String expression : expressions) {
				testCode.append(" -e \"").append(expression).append("\"");
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

			return new SubprocessResult(stdOut, stdErr, exitCode, aborted);
		} finally {
			if (generatorTempDir != null) {
				Util.recursiveDelete(generatorTempDir);
			}
		}
	}
}
