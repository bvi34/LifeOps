package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentTest {

    private fun command(q: String): TaskCommand {
        val result = TaskIntent.detect(q)
        assertTrue("expected a task command for: $q, got $result", result is TaskIntent.Result.Create)
        return (result as TaskIntent.Result.Create).command
    }

    @Test
    fun the_phrasing_from_the_bug_creates_a_task_not_a_memory() {
        // Verbatim from the app: this used to be answered by the model, which said it had added the
        // task and left only a @memorize line behind — no task anywhere.
        val command = command(
            "can you add a Todo task to life ops? maybe titled advisor improvements to the current week"
        )
        assertEquals("advisor improvements", command.title)
        assertEquals("life ops", command.target)
        assertEquals(SourceApp.LIFEOPS, command.app)
    }

    @Test
    fun a_named_title_wins_wherever_it_sits() {
        assertEquals("Publish Beacon blog post", command("add a task to lifeops called Publish Beacon blog post").title)
        assertEquals("Renew the domain", command("create a task titled Renew the domain").title)
        assertEquals("buy flour", command("add a task: buy flour").title)
        assertEquals("Call the dentist", command("please add a to-do \"Call the dentist\" to Family").title)
    }

    @Test
    fun a_title_can_simply_trail_the_noun() {
        assertEquals("buy flour", command("add a task buy flour").title)
        assertEquals("email the landlord", command("make a new task email the landlord").title)
    }

    @Test
    fun the_target_is_captured_as_the_user_said_it() {
        assertEquals("Life Ops", command("add a task to Life Ops called Fix the sink").target)
        assertEquals("beacon", command("add a task called Fix the sink to beacon").target)
        // No target named at all.
        assertNull(command("create a task titled Renew the domain").target)
    }

    @Test
    fun week_scope_is_not_part_of_the_title() {
        assertEquals("advisor improvements", command("add a task titled advisor improvements for the current week").title)
        assertEquals("advisor improvements", command("add a task to the current week titled advisor improvements").title)
        // …and "this week" is a time window, never a filing target.
        assertNull(command("add a task for this week titled advisor improvements").target)
    }

    @Test
    fun priority_is_read_when_stated() {
        assertEquals("high", command("add a high priority task to Life Ops called Fix the sink").priority)
        assertNull(command("add a task called Fix the sink").priority)
    }

    @Test
    fun a_trailing_target_keeps_a_fallback_title() {
        // "to the website" may well be part of the title; the writer restores this longer form when
        // the target turns out to name no project, aspect or category.
        val command = command("add a task called Publish blog post to the website")
        assertEquals("Publish blog post", command.title)
        assertEquals("website", command.target)
        assertEquals("Publish blog post to the website", command.titleWithTarget)
    }

    @Test
    fun questions_about_tasks_are_not_commands() {
        assertNull(TaskIntent.detect("what tasks did I add this week?"))
        assertNull(TaskIntent.detect("did you add a task?"))
        assertNull(TaskIntent.detect("how do I add a task to life ops"))
        assertNull(TaskIntent.detect("is there a task for the blog post?"))
    }

    @Test
    fun other_write_commands_are_left_to_their_own_paths() {
        assertNull(TaskIntent.detect("remember that my dog is named Rex"))
        assertNull(TaskIntent.detect("add to LLM persona that you are called Ava"))
        assertNull(TaskIntent.detect("save to memory: buy flour before Sunday"))
    }

    @Test
    fun a_nameless_command_asks_for_the_title_instead_of_inventing_one() {
        assertEquals(TaskIntent.Result.NeedsTitle, TaskIntent.detect("can you add a task?"))
        assertEquals(TaskIntent.Result.NeedsTitle, TaskIntent.detect("I need you to add a task to life ops"))
    }
}
