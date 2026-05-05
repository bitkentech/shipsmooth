package io.bitken.shipsmooth.tasks.service;

import io.bitken.shipsmooth.tasks.jaxb.*;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlService {

    private final ObjectFactory factory = new ObjectFactory();

    public record Task(int id, String name, String risk) {}

    public PlanTasks readPlanTasks(File file) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(PlanTasks.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        return (PlanTasks) unmarshaller.unmarshal(file);
    }

    public void writePlanTasks(PlanTasks planTasks, File file) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(PlanTasks.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(planTasks, file);
    }

    public List<Task> parseTasksFromPlan(String markdown) {
        List<Task> tasks = new ArrayList<>();
        Pattern pattern = Pattern.compile("^###\\s+Task\\s+(\\d+):\\s+(.+?)(?:\\s+\\[(High|Medium|Low)\\])?\\s*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(markdown);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2).trim();
            String risk = matcher.group(3) != null ? matcher.group(3).toLowerCase() : "";
            tasks.add(new Task(id, name, risk));
        }
        return tasks;
    }

    public String getPlanVersion(int planNum) {
        String planVersion = "plan-" + planNum + "-v1";
        try {
            Process process = new ProcessBuilder("git", "tag", "-l", "plan-" + planNum + "-v*", "--sort=-version:refname").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    planVersion = line.trim();
                }
            }
        } catch (IOException e) {
            // Ignore and use default
        }
        return planVersion;
    }

    public PlanTasks generatePlanTasks(int planNum, String planVersion, List<Task> tasks) throws DatatypeConfigurationException {
        PlanTasks planTasks = factory.createPlanTasks();
        planTasks.setPlan(BigInteger.valueOf(planNum));
        planTasks.setPlanVersion(planVersion);

        MetadataType metadata = factory.createMetadataType();
        metadata.setBacklogIssue("");
        metadata.setStatus(PlanStatusType.ACTIVE);
        metadata.setCreated(getXmlDate(LocalDate.now()));
        planTasks.setMetadata(metadata);

        TasksContainerType tasksContainer = factory.createTasksContainerType();
        for (Task t : tasks) {
            TaskType taskType = factory.createTaskType();
            taskType.setId(BigInteger.valueOf(t.id()));
            taskType.setName(t.name());
            taskType.setRisk(t.risk());
            taskType.setStatus(TaskStatusType.PENDING);
            taskType.setCommit("");
            taskType.setCreatedFrom(planVersion);
            taskType.setClosedAtVersion("");
            taskType.setComments(factory.createCommentsContainerType());
            taskType.setDeviations(factory.createDeviationsContainerType());
            tasksContainer.getTask().add(taskType);
        }
        planTasks.setTasks(tasksContainer);

        ProjectUpdatesContainerType updatesContainer = factory.createProjectUpdatesContainerType();
        UpdateType update = factory.createUpdateType();
        update.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        update.setMessage("Plan initialised.");
        update.setBlocked(false);
        updatesContainer.getUpdate().add(update);
        planTasks.setProjectUpdates(updatesContainer);

        return planTasks;
    }

    public void updateTaskStatus(PlanTasks planTasks, int taskId, String status) {
        TaskType task = findTask(planTasks, taskId);
        task.setStatus(TaskStatusType.fromValue(status));
    }

    public void addComment(PlanTasks planTasks, int taskId, String message) throws DatatypeConfigurationException {
        TaskType task = findTask(planTasks, taskId);
        CommentType comment = factory.createCommentType();
        comment.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        comment.setMessage(message);
        task.getComments().getComment().add(comment);
    }

    public void addDeviation(PlanTasks planTasks, int taskId, String type, String message) throws DatatypeConfigurationException {
        TaskType task = findTask(planTasks, taskId);
        DeviationType deviation = factory.createDeviationType();
        deviation.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        deviation.setType(DeviationTypeEnum.fromValue(type));
        deviation.setMessage(message);
        task.getDeviations().getDeviation().add(deviation);
    }

    public void setCommit(PlanTasks planTasks, int taskId, String commit) {
        TaskType task = findTask(planTasks, taskId);
        task.setCommit(commit);
    }

    /** Returns the raw <depends-on> string, or "" if absent. */
    public String getDependsOn(PlanTasks planTasks, int taskId) {
        TaskType task = findTask(planTasks, taskId);
        for (Object obj : task.getAny()) {
            if (obj instanceof Element el && "depends-on".equals(el.getLocalName())) {
                String text = el.getTextContent();
                return text != null ? text.trim() : "";
            }
        }
        return "";
    }

    /** Sets or replaces the <depends-on> element on the task. Pass "" to remove it. */
    public void setDependsOn(PlanTasks planTasks, int taskId, String value) throws Exception {
        TaskType task = findTask(planTasks, taskId);
        task.getAny().removeIf(obj -> obj instanceof Element el && "depends-on".equals(el.getLocalName()));
        if (value != null && !value.isBlank()) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element el = doc.createElement("depends-on");
            el.setTextContent(value.trim());
            task.getAny().add(el);
        }
    }

    public void projectUpdate(PlanTasks planTasks, String status, Boolean blocked, String message) throws DatatypeConfigurationException {
        if (status != null) {
            planTasks.getMetadata().setStatus(PlanStatusType.fromValue(status));
        }
        UpdateType update = factory.createUpdateType();
        update.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        update.setMessage(message != null ? message : "");
        update.setBlocked(blocked != null ? blocked : false);
        planTasks.getProjectUpdates().getUpdate().add(update);
    }

    public String formatPlanSummary(PlanTasks planTasks) {
        StringBuilder sb = new StringBuilder();
        MetadataType meta = planTasks.getMetadata();
        sb.append(String.format("Plan %d (%s)  status: %s  backlog: %s\n\n",
                planTasks.getPlan(),
                planTasks.getPlanVersion(),
                meta.getStatus().value(),
                meta.getBacklogIssue() != null && !meta.getBacklogIssue().isEmpty() ? meta.getBacklogIssue() : "—"));

        int idWidth = 3;
        int riskWidth = 6;
        int statusWidth = 12;
        int nameWidth = 40;

        String header = String.format("%s  %s  %s  %s  COMMIT",
                pad("ID", idWidth), pad("RISK", riskWidth), pad("STATUS", statusWidth), pad("NAME", nameWidth));
        sb.append(header).append("\n");
        sb.append("-".repeat(header.length())).append("\n");

        for (TaskType t : planTasks.getTasks().getTask()) {
            sb.append(String.format("%s  %s  %s  %s  %s\n",
                    pad(t.getId().toString(), idWidth),
                    pad(t.getRisk(), riskWidth),
                    pad(t.getStatus().value(), statusWidth),
                    pad(t.getName(), nameWidth),
                    t.getCommit() != null && !t.getCommit().isEmpty() ? t.getCommit() : "—"));
        }

        sb.append("\nProject updates:\n");
        for (UpdateType u : planTasks.getProjectUpdates().getUpdate()) {
            String flag = (u.isBlocked() != null && u.isBlocked()) ? " [BLOCKED]" : "";
            sb.append(String.format("  %s%s  %s\n", u.getTimestamp().toString(), flag, u.getMessage()));
        }

        return sb.toString();
    }

    private String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private TaskType findTask(PlanTasks planTasks, int taskId) {
        return planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == taskId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task " + taskId + " not found"));
    }

    private XMLGregorianCalendar getXmlDate(LocalDate date) throws DatatypeConfigurationException {
        return DatatypeFactory.newInstance().newXMLGregorianCalendar(date.toString());
    }

    private XMLGregorianCalendar getXmlDateTime(OffsetDateTime dateTime) throws DatatypeConfigurationException {
        return DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar.from(dateTime.toZonedDateTime()));
    }
}
