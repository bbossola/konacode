# Rulesets

A ruleset is a repository setting. GitHub does not read it from this directory. The file here
is the record of what the setting must contain, so a change to the gate arrives as a diff and
not as a memory of a click.

## `main-protect.json`

The merge gate on `main`. It holds four rules.

| Rule | Effect |
|---|---|
| `deletion` | Nobody can delete `main`. |
| `non_fast_forward` | Nobody can force-push to `main`. |
| `pull_request` | A change reaches `main` through a pull request only. |
| `required_status_checks` | The `test` check and the `Scan the dependencies` check must pass. |

The first two rules were already active. The last two are the gate.

### Why the review count is zero

GitHub does not let an author approve their own pull request. konacode has one maintainer, so
any count above zero blocks every pull request, including a dependabot one. The rule still
forces the pull request, and the checks still gate the merge.

### Why the policy is not strict

`strict_required_status_checks_policy` is `false`. A strict policy makes each pull request
update to the head of `main` before it merges. Three dependabot pull requests then rebase and
run the suite again after each merge. The cost is high and the risk it removes is small.

### The bypass actor

Role 5 is the repository admin. The admin can bypass the gate. This keeps an escape hatch for
an emergency fix.

Remove the `bypass_actors` array to make the gate apply to everybody, the owner included. This
is a deliberate choice, not a default.

## Apply the file

Read the current ruleset:

```bash
gh api repos/bbossola/konacode/rulesets
```

Update the ruleset with the file, where `<id>` is the id from the command above:

```bash
gh api --method PUT repos/bbossola/konacode/rulesets/<id> \
  --input .github/rulesets/main-protect.json
```

Create the ruleset, if no ruleset exists:

```bash
gh api --method POST repos/bbossola/konacode/rulesets \
  --input .github/rulesets/main-protect.json
```

## The check names

A required check is matched by name. The `test` name is the job id in `ci.yml`, because that
job has no `name` field. The `Scan the dependencies` name is the job name in `security.yml`.

A renamed job never reports the required check, and every pull request then waits forever.
Rename a job and update this file in one commit.

The `Fix the dependencies` job in `autofix.yml` is not a required check. It runs each day at
07:00 UTC, so it never reports on a pull request.

A pull request that the autofix opens carries no check at all. The autofix opens it with the
token that GitHub gives the workflow, and GitHub starts no workflow run from an event that this
token causes. So neither `test` nor `Scan the dependencies` reports on it. Run the test suite on
your machine before you merge, or close the pull request and open it again to start the checks.

A dependabot pull request is different. Dependabot opens it outside Actions, with its own
credentials, so both checks run.
