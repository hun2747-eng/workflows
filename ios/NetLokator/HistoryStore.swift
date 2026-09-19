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
    
    private var groupDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }
    
    public init() {
        loadHistory()
    }
    
    public func loadHistory() {
        let rawData = UserDefaults.standard.data(forKey: historyKey) ?? groupDefaults?.data(forKey: historyKey)
        guard let data = rawData else {
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
        let item = SubmittedLink(url: url, status: status, message: message)
        DispatchQueue.main.async {
            self.links.insert(item, at: 0)
            if self.links.count > 200 {
                self.links = Array(self.links.prefix(200))
            }
            self.persist()
        }
    }
    
    public func clearHistory() {
        DispatchQueue.main.async {
            self.links = []
            self.persist()
        }
    }
    
    private func persist() {
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(self.links)
            UserDefaults.standard.set(data, forKey: historyKey)
            UserDefaults.standard.synchronize()
            groupDefaults?.set(data, forKey: historyKey)
            groupDefaults?.synchronize()
        } catch {
            print("HistoryStore persist error: \(error)")
        }
    }
}
