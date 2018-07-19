package com.rus.jazz.tools.analyzeattachment;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.ibm.team.links.common.IReference;
import com.ibm.team.process.client.IProcessClientService;
import com.ibm.team.process.common.IProjectArea;
import com.ibm.team.repository.client.ILoginHandler2;
import com.ibm.team.repository.client.ILoginInfo2;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.client.TeamPlatform;
import com.ibm.team.repository.client.internal.ItemManager;
import com.ibm.team.repository.client.login.UsernameAndPasswordLoginInfo;
import com.ibm.team.repository.common.IAuditableHandle;
import com.ibm.team.repository.common.IContributor;
import com.ibm.team.repository.common.IContributorHandle;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.workitem.client.IAuditableClient;
import com.ibm.team.workitem.client.IQueryClient;
import com.ibm.team.workitem.client.IWorkItemClient;
import com.ibm.team.workitem.common.IAuditableCommon;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.expression.Term;
import com.ibm.team.workitem.common.internal.query.QueryResultIterator;
import com.ibm.team.workitem.common.model.IAttachment;
import com.ibm.team.workitem.common.model.IAttachmentHandle;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.IWorkItemReferences;
import com.ibm.team.workitem.common.model.WorkItemEndPoints;
import com.ibm.team.workitem.common.query.IQueryResult;
import com.ibm.team.workitem.common.query.IResult;

public class AnalyzeAttachments {

	private final DateFormat dateformat = new SimpleDateFormat("dd.MM.yyyy");

	/**
	 * @param args
	 * @throws TeamRepositoryException
	 * @throws UnsupportedEncodingException
	 * @throws FileNotFoundException
	 */
	@SuppressWarnings("static-access")
	public static void main(String[] args) throws TeamRepositoryException,
			FileNotFoundException, UnsupportedEncodingException {
		// prepare the command line
		Options options = new Options();
		options.addOption(OptionBuilder.isRequired().hasArgs()
				.withArgName("userID")
				.withDescription("User ID for the jazz repository connection")
				.create("user"));
		options.addOption(OptionBuilder.isRequired().hasArgs()
				.withArgName("password")
				.withDescription("Password for the jazz repository connection")
				.create("pwd"));
		options.addOption(OptionBuilder
				.isRequired()
				.hasArgs()
				.withArgName("repository URL")
				.withDescription(
						"URL for the RTC repository connection, e.g. https://<server>/ccm")
				.create("repository"));
		options.addOption(OptionBuilder
				.isRequired()
				.hasArgs()
				.withArgName("process project area")
				.withDescription(
						"Name of project area that contains the process")
				.create("process"));

		try {
			CommandLineParser parser = new BasicParser();
			CommandLine cmd = parser.parse(options, args);

			String user = cmd.getOptionValue("user");
			String pwd = cmd.getOptionValue("pwd");
			String repository = cmd.getOptionValue("repository");
			String process = cmd.getOptionValue("process");

			AnalyzeAttachments analyze = new AnalyzeAttachments();
			analyze.execute(user, pwd, repository, process);

		} catch (ParseException e) {
			System.out.println(e.getMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("WorkfkowStatusUpdater", options);
		}
	}

	@SuppressWarnings("rawtypes")
	private void execute(final String user, final String pwd,
			final String repository, final String process)
			throws TeamRepositoryException, FileNotFoundException,
			UnsupportedEncodingException {
		IProgressMonitor monitor = null;
		ITeamRepository teamRepository = null;

		TeamPlatform.startup();
		teamRepository = TeamPlatform.getTeamRepositoryService()
				.getTeamRepository(repository);
		teamRepository.registerLoginHandler(new ILoginHandler2() {
			@Override
			public ILoginInfo2 challenge(ITeamRepository repo) {
				return new UsernameAndPasswordLoginInfo(user, pwd);
			}
		});
		teamRepository.login(monitor);
		System.out.println("Logged in to " + repository);

		System.out.println("Load necessary services");
		IProcessClientService processClient = (IProcessClientService) teamRepository
				.getClientLibrary(IProcessClientService.class);
		IWorkItemClient workItemClient = (IWorkItemClient) teamRepository
				.getClientLibrary(IWorkItemClient.class);

		URI uri = URI.create(process.replace(" ", "%20"));
		IProjectArea masterProcess = (IProjectArea) processClient
				.findProcessArea(uri, null, null);

		System.out.println("Run and execute query for all work items");
		long start = System.currentTimeMillis();
		Term term = new Term(Term.Operator.AND);

		IQueryClient queryClient = workItemClient.getQueryClient();
		IQueryResult unresolvedResults = queryClient.getExpressionResults(
				masterProcess, term);
		((QueryResultIterator) unresolvedResults).setLimit(Integer.MAX_VALUE);

		IAuditableCommon auditableCommon = (IAuditableCommon) teamRepository
				.getClientLibrary(IAuditableCommon.class);
		IWorkItemCommon common = (IWorkItemCommon) teamRepository
				.getClientLibrary(IWorkItemCommon.class);

		System.out.println(unresolvedResults.getResultSize(monitor).getTotal()
				+ " items found");

		long number = 0;
		long attachments = 0;
		PrintWriter writer = new PrintWriter("AttachmentList.csv", "UTF-8");
		writer.println("Name; Attachment ID; Work Item ID; size; owner; date");
		while (unresolvedResults.hasNext(monitor)) {
			IResult result = (IResult) unresolvedResults.next(monitor);
			IWorkItem workItem = auditableCommon.resolveAuditable(
					(IAuditableHandle) result.getItem(),
					IWorkItem.FULL_PROFILE, monitor);

			IWorkItemReferences workitemReferences = common
					.resolveWorkItemReferences(workItem, null);
			List<IReference> references = workitemReferences
					.getReferences(WorkItemEndPoints.ATTACHMENT);

			for (IReference iReference : references) {
				IAttachmentHandle attachHandle = (IAttachmentHandle) iReference
						.resolve();
				IAuditableClient auditableClient = (IAuditableClient) teamRepository
						.getClientLibrary(IAuditableClient.class);
				IAttachment attachment = (IAttachment) auditableClient
						.resolveAuditable((IAttachmentHandle) attachHandle,
								IAttachment.DEFAULT_PROFILE, null);

				IContributorHandle creatorHandle = attachment.getCreator();
				IContributor creator = (IContributor) teamRepository
						.itemManager().fetchCompleteItem(creatorHandle,
								ItemManager.REFRESH, monitor);
				Timestamp creationDate = attachment.getCreationDate();

				System.out.println("Attachment " + attachment.getName()
						+ "[ID=" + attachment.getId() + "] found on work item "
						+ workItem.getId() + " ("
						+ attachment.getContent().getEstimatedConvertedLength()
						+ " bytes)");
				writer.println(attachment.getName() + ";" + attachment.getId()
						+ ";" + workItem.getId() + ";"
						+ attachment.getContent().getEstimatedConvertedLength()
						+ ";" + creator.getUserId() + ";"
						+ dateformat.format(creationDate));
				attachments++;

			}
			number++;
		}
		long finished = System.currentTimeMillis();
		long executionTimeSeconds = (finished - start) / 1000;
		System.out.println(attachments + " attachments found in " + number
				+ " work items in " + executionTimeSeconds + " s");
		writer.close();
	}
}
