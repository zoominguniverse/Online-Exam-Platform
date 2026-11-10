package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.QuestionBank.Answer;
import com.onlineexam.platform.domain.QuestionBank.Draft;
import com.onlineexam.platform.domain.QuestionBank.Option;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.domain.QuestionBank.Summary;
import com.onlineexam.platform.domain.QuestionBank.Type;
import com.onlineexam.platform.domain.QuestionBank.Version;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.AttachmentRepository;
import com.onlineexam.platform.repository.QuestionRepository;
import com.onlineexam.platform.repository.SubjectRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private static final Safelist HTML = Safelist.none()
            .addTags("p", "div", "span", "br", "strong", "em", "b", "i", "u", "code", "pre", "sup", "sub",
                    "ol", "ul", "li", "blockquote", "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td", "img")
            .addAttributes("span", "data-gap", "data-tex").addAttributes("img", "data-attachment", "alt");

    private final QuestionRepository questions;
    private final SubjectRepository subjects;
    private final AccountRepository accounts;
    private final AttachmentRepository attachments;

    public QuestionService(QuestionRepository questions, SubjectRepository subjects, AccountRepository accounts,
                           AttachmentRepository attachments) {
        this.questions = questions;
        this.subjects = subjects;
        this.accounts = accounts;
        this.attachments = attachments;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Summary> list(UUID subject, Type type, int page, int size) {
        SubjectService.checkPage(page, size);
        return new Page<>(questions.list(subject, type, size, (long) (page - 1) * size),
                questions.count(subject, type), page, size);
    }

    public Version get(UUID id, Integer version) {
        return questions.get(id, version).orElseThrow(QuestionService::missing);
    }

    @Transactional
    public Version create(UUID author, Draft input) {
        Draft draft = validate(input);
        UUID id = UUID.randomUUID();
        questions.insert(id, draft.subjectId());
        questions.insertVersion(id, 1, draft, minor(draft.score()));
        linkImages(author, id, 1, draft);
        return get(id, null);
    }

    @Transactional
    public Version edit(UUID author, UUID id, int expectedVersion, Draft input) {
        int next = checkActive(id, expectedVersion) + 1;
        Draft draft = validate(input);
        questions.insertVersion(id, next, draft, minor(draft.score()));
        linkImages(author, id, next, draft);
        questions.advance(id, next, draft.subjectId());
        return get(id, null);
    }

    @Transactional
    public void archive(UUID id, int version) {
        checkActive(id, version);
        questions.archive(id);
    }

    private int checkActive(UUID id, int version) {
        SubjectService.requireVersion(version);
        int current = questions.lockActive(id).orElseThrow(QuestionService::missing);
        if (current != version) throw ApiException.conflict("题目已更新，请刷新后重试。");
        return current;
    }

    private Draft validate(Draft input) {
        if (input == null || input.type() == null) throw ApiException.validation("type 必须是支持的题型。");
        if (input.difficulty() < 1 || input.difficulty() > 5) throw ApiException.validation("difficulty 范围是 1–5。");
        minor(input.score());
        if (input.subjectId() == null || subjects.get(input.subjectId()).filter(s -> accounts.gradeEnabled(s.gradeId())).isEmpty()) {
            throw ApiException.validation("subjectId 必须属于启用年级的学科。");
        }
        String stem = clean(input.stemHtml(), "stemHtml");
        var parsed = Jsoup.parseBodyFragment(stem);
        if (parsed.text().isBlank() && parsed.select("span[data-tex], img").isEmpty()) {
            throw ApiException.validation("stemHtml 需要可显示内容。");
        }
        List<Option> options = input.options() == null ? List.of() : input.options();
        Answer answer = input.answer();
        if (answer == null) throw ApiException.validation("answer 必填。");
        var gaps = parsed.select("span[data-gap]").eachAttr("data-gap");
        if (input.type() != Type.GAP && !gaps.isEmpty()) throw ApiException.validation("非填空题不能含有空位。");

        switch (input.type()) {
            case SINGLE, MULTIPLE -> validateChoice(input.type(), options, answer);
            case TRUE_FALSE -> {
                if (!options.isEmpty() || answer.optionIds() == null
                        || !List.of(List.of("true"), List.of("false")).contains(answer.optionIds())
                        || answer.gaps() != null || answer.text() != null) {
                    throw ApiException.validation("判断题 answer.optionIds 必须只包含 true 或 false。");
                }
            }
            case GAP -> {
                if (!options.isEmpty() || answer.optionIds() != null || answer.text() != null
                        || answer.gaps() == null || answer.gaps().size() != gaps.size()
                        || gaps.isEmpty() || gaps.size() > 20
                        || !gaps.equals(IntStream.rangeClosed(1, gaps.size()).mapToObj(Integer::toString).toList())
                        || answer.gaps().stream().anyMatch(gap -> !validText(gap, 1000))) {
                    throw ApiException.validation("answer.gaps 必须与题干中按顺序编号的 1–20 个空位一一对应。");
                }
            }
            case SHORT -> {
                if (!options.isEmpty() || answer.optionIds() != null || answer.gaps() != null
                        || !validText(answer.text(), 10000)) {
                    throw ApiException.validation("简答题 answer.text 需要 1–10000 个字符。");
                }
            }
        }
        return new Draft(input.subjectId(), input.type(), input.difficulty(), input.score(), stem,
                options, answer, clean(input.explanationHtml(), "explanationHtml"));
    }

    private static void validateChoice(Type type, List<Option> options, Answer answer) {
        if (options.size() < 2 || options.size() > 10 || options.stream().anyMatch(option -> option == null
                || option.id() == null || !option.id().matches("[A-Za-z0-9_-]{1,40}") || !validText(option.text(), 2000))) {
            throw ApiException.validation("options 需要 2–10 个带有效标识与文本的选项。");
        }
        Set<String> ids = new HashSet<>();
        for (Option option : options) {
            if (!ids.add(option.id())) throw ApiException.validation("options 中的选项标识不能重复。");
        }
        var selected = answer.optionIds();
        if (selected == null || answer.text() != null || answer.gaps() != null
                || (type == Type.SINGLE ? selected.size() != 1 : selected.size() < 2)
                || new HashSet<>(selected).size() != selected.size() || !ids.containsAll(selected)) {
            throw ApiException.validation("answer.optionIds 必须包含符合题型的不同有效选项。");
        }
    }

    private static boolean validText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max && text.indexOf(0) < 0;
    }

    private static String clean(String html, String field) {
        if (html == null) return "";
        if (html.length() > 10000 || html.indexOf(0) >= 0) {
            throw ApiException.validation(field + " 不能超过 10000 个字符且不能含空字符。");
        }
        var settings = new Document.OutputSettings().prettyPrint(false);
        var parsed = Jsoup.parseBodyFragment(Jsoup.clean(html, "", HTML, settings));
        parsed.outputSettings(settings);
        for (Element image : parsed.select("img")) {
            String id = image.attr("data-attachment");
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw ApiException.validation(field + " 只能使用已上传图片。");
            }
        }
        return parsed.body().html();
    }

    private void linkImages(UUID author, UUID question, int version, Draft draft) {
        var stem = imageIds(draft.stemHtml());
        var explanation = imageIds(draft.explanationHtml());
        var images = new java.util.TreeSet<>(stem);
        images.addAll(explanation);
        if (images.size() > 20) throw ApiException.validation("每道题最多引用 20 张图片。");
        for (UUID image : images) {
            if (!attachments.mayLink(image, author, question)) throw ApiException.validation("题目引用了不可用的图片。");
            if (stem.contains(image)) attachments.link(image, question, version, "STEM");
            if (explanation.contains(image)) attachments.link(image, question, version, "EXPLANATION");
        }
    }

    private static Set<UUID> imageIds(String html) {
        return Jsoup.parseBodyFragment(html).select("img").eachAttr("data-attachment").stream()
                .map(UUID::fromString).collect(java.util.stream.Collectors.toSet());
    }

    private static int minor(String score) {
        if (score == null || !score.matches("[0-9]{1,6}\\.[0-9]{2}")) {
            throw ApiException.validation("score 必须是保留两位小数的正分值。");
        }
        int minor = new BigDecimal(score).movePointRight(2).intValueExact();
        if (minor == 0) throw ApiException.validation("score 必须大于零。");
        return minor;
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "题目不存在或已归档。");
    }
}
