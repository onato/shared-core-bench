/// The contract calls a core's adapter answers, each taking the step's `args` and returning its result.
protocol ContractCore {
    func ingest(_ args: [String: JSON]) -> JSON
    func page(_ args: [String: JSON]) -> JSON
    func nextFetch(_ args: [String: JSON]) -> JSON
    func detail(_ args: [String: JSON]) -> JSON
    func stats(_ args: [String: JSON]) -> JSON
    func refresh(_ args: [String: JSON]) -> JSON
    func categories(_ args: [String: JSON]) -> JSON
}
