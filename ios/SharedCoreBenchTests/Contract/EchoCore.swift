/// Answers every step with the step's name and the arguments it was given.
struct EchoCore: ContractCore {
    func ingest(_ args: [String: JSON]) -> JSON { echo("ingest", args) }
    func page(_ args: [String: JSON]) -> JSON { echo("page", args) }
    func nextFetch(_ args: [String: JSON]) -> JSON { echo("nextFetch", args) }
    func detail(_ args: [String: JSON]) -> JSON { echo("detail", args) }
    func stats(_ args: [String: JSON]) -> JSON { echo("stats", args) }
    func refresh(_ args: [String: JSON]) -> JSON { echo("refresh", args) }
    func categories(_ args: [String: JSON]) -> JSON { echo("categories", args) }

    private func echo(_ step: String, _ args: [String: JSON]) -> JSON {
        .object(["step": .string(step), "args": .object(args)])
    }
}
