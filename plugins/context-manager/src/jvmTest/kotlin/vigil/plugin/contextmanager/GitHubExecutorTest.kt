package vigil.plugin.contextmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubExecutorTest {

    private val executor = GitHubExecutor()

    // --- parseIssueNumber ---

    @Test
    fun `parseIssueNumber extracts number from feat branch`() {
        assertEquals(22, executor.parseIssueNumber("feat/22-add-login"))
    }

    @Test
    fun `parseIssueNumber extracts number from issue prefix`() {
        assertEquals(15, executor.parseIssueNumber("fix/issue-15-bug"))
    }

    @Test
    fun `parseIssueNumber extracts standalone number`() {
        assertEquals(7, executor.parseIssueNumber("improve/7"))
    }

    @Test
    fun `parseIssueNumber returns null when no number present`() {
        assertNull(executor.parseIssueNumber("feat/plugin-context-manager"))
    }

    @Test
    fun `parseIssueNumber returns null for bare branch`() {
        assertNull(executor.parseIssueNumber("main"))
    }

    // --- parsePR ---

    @Test
    fun `parsePR extracts PR info from valid JSON`() {
        val json = """[{"number":23,"title":"スナップショット改善","state":"OPEN"}]"""
        val pr = executor.parsePR(json)
        assertNotNull(pr)
        assertEquals(23, pr.number)
        assertEquals("スナップショット改善", pr.title)
        assertEquals("OPEN", pr.state)
    }

    @Test
    fun `parsePR returns null for empty array`() {
        assertNull(executor.parsePR("[]"))
    }

    @Test
    fun `parsePR returns null for blank input`() {
        assertNull(executor.parsePR(""))
    }

    // --- parseIssue ---

    @Test
    fun `parseIssue extracts issue info from valid JSON`() {
        val json = """{"title":"Issue title","body":"- [x] done\n- [ ] todo","state":"OPEN"}"""
        val issue = executor.parseIssue(json)
        assertNotNull(issue)
        assertEquals("Issue title", issue.title)
        assertEquals("OPEN", issue.state)
        assert(issue.body.contains("- [x] done"))
    }

    @Test
    fun `parseIssue handles escaped body content`() {
        val json = """{"title":"Test","body":"line1\nline2\n\"quoted\"","state":"CLOSED"}"""
        val issue = executor.parseIssue(json)
        assertNotNull(issue)
        assertEquals("line1\nline2\n\"quoted\"", issue.body)
    }

    @Test
    fun `parseIssue returns null for blank input`() {
        assertNull(executor.parseIssue(""))
    }
}
