import Foundation

public class NetworkService {
    public static let shared = NetworkService()
    private let appGroupID = "group.hu.netlokator.share"
    
    private var userDefaults: UserDefaults {
        UserDefaults(suiteName: appGroupID) ?? UserDefaults.standard
    }
    
    public var baseUrl: String {
        get { userDefaults.string(forKey: "baseUrl") ?? "" }
        set { userDefaults.set(newValue, forKey: "baseUrl"); userDefaults.synchronize() }
    }
    
    public var apiKey: String {
        get { userDefaults.string(forKey: "apiKey") ?? "" }
        set { userDefaults.set(newValue, forKey: "apiKey"); userDefaults.synchronize() }
    }
    
    public var isLoggedIn: Bool {
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    
    // Resolve multi-hop redirects (e.g. share.google -> destination)
    public func resolveDestinationUrl(from initialUrlString: String) async -> String {
        var currentUrlString = initialUrlString.trimmingCharacters(in: .whitespacesAndNewlines)
        var hops = 0
        let maxHops = 12
        
        while hops < maxHops {
            hops += 1
            // Unwrap query parameters first (url, q, dest, target)
            currentUrlString = unwrapParam(from: currentUrlString)
            
            guard let url = URL(string: currentUrlString) else { break }
            let host = url.host?.lowercased() ?? ""
            
            // Only follow redirects for known redirectors or shortened domains
            let shouldFollow = host.contains("share.google") ||
                               host.contains("google.com") ||
                               host.contains("goo.gl") ||
                               host.contains("t.co") ||
                               host.contains("bit.ly") ||
                               host.contains("tinyurl.com")
                               
            if !shouldFollow {
                break
            }
            
            // Custom session with redirects disabled to inspect 3xx Location header
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.timeoutInterval = 8
            request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
            
            let delegate = NoRedirectDelegate()
            let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
            
            do {
                let (_, response) = try await session.data(for: request)
                if let httpResponse = response as? HTTPURLResponse, (300...399).contains(httpResponse.statusCode) {
                    if let location = httpResponse.value(forHTTPHeaderField: "Location") {
                        if let resolved = URL(string: location, relativeTo: url)?.absoluteString {
                            currentUrlString = resolved
                            continue
                        }
                    }
                }
                break
            } catch {
                break
            }
        }
        
        return unwrapParam(from: currentUrlString)
    }
    
    private func unwrapParam(from urlString: String) -> String {
        guard let components = URLComponents(string: urlString), let queryItems = components.queryItems else {
            return urlString
        }
        let checkKeys = ["url", "q", "dest", "target", "link"]
        for key in checkKeys {
            if let match = queryItems.first(where: { bash.name.lowercased() == key })?.value {
                if match.hasPrefix("http://") || match.hasPrefix("https://") {
                    return unwrapParam(from: match)
                }
            }
        }
        return urlString
    }
    
    public func sendUrl(rawUrl: String) async throws -> (success: Bool, message: String, finalUrl: String) {
        guard isLoggedIn else {
            throw NSError(domain: "NetLokator", code: 401, userInfo: [NSLocalizedDescriptionKey: "Nincs beállítva a Base URL vagy API-kulcs."])
        }
        
        let finalUrl = await resolveDestinationUrl(from: rawUrl)
        let cleanedBaseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let endpoint = URL(string: "\(cleanedBaseUrl)/api/extension/index-url") else {
            throw NSError(domain: "NetLokator", code: 400, userInfo: [NSLocalizedDescriptionKey: "Érvénytelen végpont URL."])
        }
        
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "X-API-Key")
        
        let bodyPayload: [String: String] = ["url": finalUrl]
        request.httpBody = try JSONSerialization.data(withJSONObject: bodyPayload)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NSError(domain: "NetLokator", code: 500, userInfo: [NSLocalizedDescriptionKey: "Nem érkezett érvényes HTTP válasz."])
        }
        
        var message = ""
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let msg = json["message"] as? String {
            message = msg
        } else if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            message = text
        } else {
            message = (httpResponse.statusCode == 200 || httpResponse.statusCode == 201) ? "URL sikeresen elküldve!" : "Hiba (HTTP \(httpResponse.statusCode))"
        }
        
        let success = (200...299).contains(httpResponse.statusCode)
        HistoryStore.shared.addLink(url: finalUrl, status: success ? "Sikeres" : "Hiba", message: message)
        return (success, message, finalUrl)
    }
}

private class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Stop redirect to inspect Location header
        completionHandler(nil)
    }
}
