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

package de.tuclausthal.submissioninterface.servlets.view;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import de.tuclausthal.submissioninterface.persistence.datamodel.DockerTestStep;
import de.tuclausthal.submissioninterface.persistence.datamodel.HaskellRuntimeTest;
import de.tuclausthal.submissioninterface.persistence.datamodel.HaskellRuntimeTestIdentifier;
import de.tuclausthal.submissioninterface.servlets.GATEView;
import de.tuclausthal.submissioninterface.servlets.controller.HaskellRuntimeTestManager;
import de.tuclausthal.submissioninterface.servlets.controller.TaskManager;
import de.tuclausthal.submissioninterface.template.Template;
import de.tuclausthal.submissioninterface.template.TemplateFactory;
import de.tuclausthal.submissioninterface.util.Util;

/**
 * View-Servlet for clustering haskell submissions based on common errors (dynamic/runtime analysis)
 *
 * @author Christian Wagner
 */
@GATEView
public class HaskellRuntimeTestManagerView extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		Template template = TemplateFactory.getTemplate(request, response);

		HaskellRuntimeTest test = (HaskellRuntimeTest) request.getAttribute("test");

		HttpSession httpSession = request.getSession(false);

		template.addKeepAlive();
		template.printEditTaskTemplateHeader("Haskell Runtime Test bearbeiten", test.getTask());

		PrintWriter out = response.getWriter();

		if (httpSession != null) {
			out.println(errorBoxIfErrorOccurred(httpSession, "haskellRuntimeTestBrowseError", "Beim Analysieren der Musterlösung ist ein Fehler aufgetreten"));
			out.println(errorBoxIfErrorOccurred(httpSession, "haskellRuntimeTestGenerateError", "Beim Generieren der Testfälle ist ein Fehler aufgetreten"));
		}

		out.println("""
				<script>
					function disableGeneratorCalls(button) {
						const rect = button.getBoundingClientRect();
				        button.style.width = rect.width + "px";
				        button.style.height = rect.height + "px";
						button.innerHTML = '<span class="spinner"></span>';
						Array.from(document.getElementsByClassName('generatorCaller')).forEach(b => b.disabled = true);
					}
					function submitGeneratorForm(formId, button) {
						const f = document.getElementById(formId);
						if (f.reportValidity()) {
							disableGeneratorCalls(button);
							f.submit();
						}
					}
				</script>
				<style>
					span.spinner {
						width: 0.8em;
						height: 0.8em;
						border: 2px solid #ccc;
						border-top: 2px solid #333;
						border-radius: 50%;
						animation: spinLoadingAnimation 0.6s linear infinite;
						display: inline-block;
						vertical-align: middle;
					}
					@keyframes spinLoadingAnimation {
						to { transform: rotate(360deg); }
					}
				</style>
				""");

		out.println("<link href=\"" + request.getContextPath() + "/assets/prism/prism.css\" rel=\"stylesheet\">");

		// similar code in TestManagerAddTestFormView
		out.println("<h2>" + Util.escapeHTML(test.getTestTitle()) + "</h2>");
		out.println("<form action=\"" + Util.generateHTMLLink("?", response) + "\" method=post>");
		out.println("<input type=hidden name=testid value=\"" + test.getId() + "\">");
		out.println("<input type=hidden name=action value=edittest>");
		out.println("<table>");
		out.println("<tr>");
		out.println("<th>Titel:</th>");
		out.println("<td><input type=text name=title maxlength=250 size=60 value=\"" + Util.escapeHTML(test.getTestTitle()) + "\" required></td>");
		out.println("</tr>");
		out.println("<tr>");
		out.println("<th>Tutorentest:</th>");
		out.println("<td><input type=checkbox name=tutortest" + (test.isForTutors() ? " checked" : "") + "> (Ergebnis wird den TutorInnen zur Korrektur angezeigt)</td>");
		out.println("</tr>");
		out.println("<tr>");
		out.println("<th># ausführbar für Studierende:</th>");
		out.println("<td><input type=text name=timesRunnableByStudents value=\"" + test.getTimesRunnableByStudents() + "\" required=required pattern=\"[0-9]+\"></td>");
		out.println("</tr>");
		out.println("<tr>");
		out.println("<th>Studierenden Test-Details anzeigen:</th>");
		out.println("<td><input type=checkbox name=giveDetailsToStudents" + (test.isGiveDetailsToStudents() ? " checked" : "") + "></td>");
		out.println("</tr>");
		out.println("<tr>");
		out.println("<th>Preparation Code:</th>");
		out.println("<td><textarea cols=60 rows=10 name=preparationcode>" + Util.escapeHTML(test.getPreparationShellCode()) + "</textarea></td>");
		out.println("</tr>");
		out.println("<tr>");
		out.print("<td colspan=2 class=mid><input type=submit value=speichern> <a href=\"");
		out.print(Util.generateHTMLLink(TaskManager.class.getSimpleName() + "?action=editTask&taskid=" + test.getTask().getTaskid() + "&lecture=" + test.getTask().getTaskGroup().getLecture().getId(), response));
		out.println("\">Abbrechen</a></td>");
		out.println("</tr>");
		out.println("</table>");
		out.println("</form>");

		out.println("<hr>");

		out.println("<h2>Benutzerdefinierte Haskell Funktionen und Datentypen der Musterlösung</h2>");
		StringBuilder newtypeOrDatasHtml = new StringBuilder();
		newtypeOrDatasHtml.append("""
				<tr>
					<th colspan="1">Typname</th>
					<th colspan="4">Typdefinition und <code>Arbitrary</code> Instanz</th>
				</tr>
				""");

		StringBuilder functionsHtml = new StringBuilder();
		functionsHtml.append("""
				<tr>
					<th>Funktion</th>
					<th>Typsignatur</th>
					<th>Typsignatur (+d)</th>
					<th>Konkrete Typsignatur</th>
					<th>Generator ausführen</th>
				</tr>
				""");

		boolean showFunctionTable = false;
		boolean showNewtypeOrDataTable = false;

		for (HaskellRuntimeTestIdentifier identifier : test.getIdentifiers()) {
			switch (identifier.getIdentifierClass()) {
				case "newtypeordata":
					showNewtypeOrDataTable = true;
					newtypeOrDatasHtml.append(String.format("""
							<tr>
								<td colspan="1"><code class="language-haskell">%1$s</code></td>
								<td colspan="4">
									<details style="width: 60vw;">
										<summary><code class="language-haskell">%2$s</code></summary>
										<pre style="overflow-x: auto; white-space: pre; max-width: 100%%"><code class="language-haskell">%3$s</code></pre>
									</details>
								</td>
							</tr>
							""", Util.escapeHTML(identifier.getNewtypeOrDataTypename()), Util.escapeHTML(identifier.getNewtypeOrDataDefinition()), Util.escapeHTML(identifier.getNewtypeOrDataArbitraryInstance())));
					break; //TODO@CHW: 60vw might not be a good width depending on the page template
				case "function":
					showFunctionTable = true;
					functionsHtml.append(String.format("""
							<tr>
								<td><code class="language-haskell">%2$s</code></td>
								<td><code class="language-haskell">%3$s</code></td>
								<td><code class="language-haskell">%6$s</code></td>
								<td><code class="language-haskell">%7$s</code></td>
								<td style="text-align: center;">
									<form action="%4$s" method="post" id="generateFunctionTestcasesForm%1$s">
										<input type=hidden name=testid value=%5$s>
										<input type=hidden name=identifierid value=%1$s>
										<input type=hidden name=action value=generateFunctionTestcases>
										<input type="text" name="numberOfTestSteps" value="10" required="required" pattern="^[1-9][0-9]?$" style="width: 3ch">
										<button class="generatorCaller"
												onclick="submitGeneratorForm('generateFunctionTestcasesForm%1$s', this)">
											Testfälle generieren
										</button>
									</form>
								</td>
							</tr>
							""", identifier.getIdentifierid(), Util.escapeHTML(identifier.getFunctionName()), Util.escapeHTML(identifier.getFunctionType()), Util.generateHTMLLink("?", response), test.getId(), Util.escapeHTML(identifier.getFunctionDefaultType()), Util.escapeHTML(identifier.getFunctionConcreteType())));
					break;
			}
		}

		out.println("<table style=\"border: none;\">");
		if (showNewtypeOrDataTable) {
			out.println(newtypeOrDatasHtml);
		}
		if (showNewtypeOrDataTable && showFunctionTable) {
			out.println("<tr style=\"border: none;\"><td style=\"height: 12px; border: none;\" colspan=\"100%\"></td></tr>");
		}
		if (showFunctionTable) {
			out.println(functionsHtml);
		}
		out.println("</table>");

		out.println(String.format("""
				<br>
				<div align="center">
					<form action="%1$s" method=post id=browseModelSolutionForm>
						<input type=hidden name=testid value="%2$s">
						<input type=hidden name=action value=browseModelSolution>
						<button class="generatorCaller"
							onclick="submitGeneratorForm('browseModelSolutionForm', this)">
							Musterlösung analysieren (:browse)
						</button>
					</form>
				</div>
				""", Util.generateHTMLLink("?", response), test.getId()));

		out.println("<h2>Testschritte bearbeiten</h2>");
		out.println("<table>");
		out.println(/* @formatter:off */
			"<thead>" +
				"<tr>" +
					"<th>Titel</th>" +
					"<th>Testcode</th>" +
					"<th>Expected</th>" +
				"</tr>" +
			"</thead>"
		/* @formatter:on */);

		for (DockerTestStep step : test.getTestSteps()) {
			String deleteTestStepLink = Util.generateHTMLLink(HaskellRuntimeTestManager.class.getSimpleName() + "?testid=" + test.getId() + "&action=deleteStep&teststepid=" + step.getTeststepid(), response);
			out.println(/* @formatter:off */
				"<tr>" +
					"<td>" +
						Util.escapeHTML(step.getTitle()) + " " +
						"<a onclick=\"return sendAsPost(this, 'Wirklich löschen?')\" href=\"" + deleteTestStepLink + "\">" +
						    "(Löschen)" +
						"</a>" +
					"</td>" +
					"<td><code class=\"language-haskell\">" + Util.escapeHTML(step.getTestcode()) + "</code></td>" +
					"<td><code class=\"language-haskell\">" + Util.escapeHTML(step.getExpect()) + "</code></td>" +
				"</tr>"
			/* @formatter:on */);
		}
		out.println("</table>");

		out.println("<script src=\"" + request.getContextPath() + "/assets/prism/prism.js\" defer></script>");
		template.printTemplateFooter();
	}

	private String errorBoxIfErrorOccurred(HttpSession httpSession, String httpSessionAttributeName, String errorTitle) {
		String errorMessage = (String) httpSession.getAttribute(httpSessionAttributeName);
		httpSession.removeAttribute(httpSessionAttributeName);

		return (errorMessage == null) ? "" : String.format("""
				<div style="border: 1px solid red;">
					<div style="background-color: red; color: white; padding: 4px; font-weight: bold; overflow: auto">
						%1$s
					</div>
					<div style="background-color: #ffe5e5; color: red; padding: 4px; overflow: auto;">
						<pre>%2$s</pre>
					</div>
				</div>
				<br>
				<script>alert("%1$s")</script>
				""", errorTitle, Util.escapeHTML(errorMessage));
	}
}
