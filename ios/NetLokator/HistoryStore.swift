import Foundation

public struct SubmittedLink: Codable, Identifiable {
    public let id: String
    public let url: String
    public let timestamp: Date
    public let status: String
    public let message: String
    
    public init(id: String = UUID().uuidString, url: String, timestamp: Date = Date(), status: String, message: String) {
        self.id = id
        self.url = url
        self.timestamp = timestamp
        self.status = status
        self.message = message
    }
}

public class HistoryStore: ObservableObject {
    public static let shared = HistoryStore()
    private let appGroupID = "group.hu.netlokator.share"
    private let historyKey = "submitted_links_history"
    
    @Published public var links: [SubmittedLink] = []
    
    private var userDefaults: UserDefaults {
        UserDefaults(suiteName: appGroupID) ?? UserDefaults.standard
    }
    
    public init() {
        loadHistory()
    }
    
    public func loadHistory() {
        guard let data = userDefaults.data(forKey: historyKey) else {
            self.links = []
            return
        }
        do {
            let decoder = JSONDecoder()
            let decoded = try decoder.decode([SubmittedLink].self, from: data)
            self.links = decoded.sorted(by: { $0.timestamp > $1.timestamp })
        } catch {
            self.links = []
        }
    }
    
    public func addLink(url: String, status: String, message: String) {
        loadHistory()
        let newEntry = SubmittedLink(url: url, timestamp: Date(), status: status, message: message)
        var updated = self.links
        // Avoid duplicate within 3 seconds
        if let first = updated.first, first.url == url, abs(first.timestamp.timeIntervalSinceNow) < 3.0 {
            return
        }
        updated.insert(newEntry, at: 0)
        if updated.count > 200 {
            updated = Array(updated.prefix(200))
        }
        self.links = updated
        saveHistory()
    }
    
    public func clearHistory() {
        self.links = []
        userDefaults.removeObject(forKey: historyKey)
        userDefaults.synchronize()
    }
    
    private func saveHistory() {
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(self.links)
            userDefaults.set(data, forKey: historyKey)
            userDefaults.synchronize()
        } catch {
            print("Failed to save history: \(error)")
        }
    }
}
