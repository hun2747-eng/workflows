import Foundation

public class NetworkService {
    public static let shared = NetworkService()
    private let appGroupID = "group.hu.netlokator.share"

    // Beégetett tartalék kulcs: a megosztás-bővítmény App Group nélkül (sideload)
    // nem tud a fő appból kulcsot olvasni – ez a fallback biztosítja a hitelesítést.
    private static let bakedInApiKey = "e00140c8a085b808563a568788910ccc9e005659704fddfc05108acf4082177c"

    private var groupDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    public var baseUrl: String {
        get {
            let standard = UserDefaults.standard.string(forKey: "baseUrl")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !standard.isEmpty { return standard }
            if let group = groupDefaults?.string(forKey: "baseUrl")?.trimmingCharacters(in: .whitespacesAndNewlines), !group.isEmpty {
                return group
            }
            return "https://netlokator.hu"
        }
        set {
            let val = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            let finalVal = val.isEmpty ? "https://netlokator.hu" : val
            UserDefaults.standard.set(finalVal, forKey: "baseUrl")
            UserDefaults.standard.synchronize()
            groupDefaults?.set(finalVal, forKey: "baseUrl")
            groupDefaults?.synchronize()
        }
    }

    public var apiKey: String {
        get {
            let standard = UserDefaults.standard.string(forKey: "apiKey")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !standard.isEmpty { return standard }
            if let group = groupDefaults?.string(forKey: "apiKey")?.trimmingCharacters(in: .whitespacesAndNewlines), !group.isEmpty {
                return group
            }
            return NetworkService.bakedInApiKey
        }
        set {
            let val = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            UserDefaults.standard.set(val, forKey: "apiKey")
            UserDefaults.standard.synchronize()
            groupDefaults?.set(val, forKey: "apiKey")
            groupDefaults?.synchronize()
        }
    }

    public var isLoggedIn: Bool {
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public func resolveDestinationUrl(from initialUrlString: String) async -> String {
        var currentUrlString = initialUrlString.trimmingCharacters(in: .whitespacesAndNewlines)
        var hops = 0
        let maxHops = 12

        while hops < maxHops {
            hops += 1
            currentUrlString = unwrapParam(from: currentUrlString)
            guard let url = URL(string: currentUrlString) else { break }
            let host = url.host?.lowercased() ?? ""
            let knownRedirectors = ["share.google", "goo.gl", "bit.ly", "t.co", "tinyurl.com", "ow.ly", "buff.ly", "is.gd", "cutt.ly"]
            let isKnown = knownRedirectors.contains(where: { host == $0 || host.hasSuffix("." + $0) })

            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.setValue("NetLokator-iOS/1.0", forHTTPHeaderField: "User-Agent")

            let delegate = NoRedirectDelegate()
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 8
            let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)

            do {
                let (_, response) = try await session.data(for: request)
                if let httpResponse = response as? HTTPURLResponse {
                    if (300...399).contains(httpResponse.statusCode),
                       let location = httpResponse.value(forHTTPHeaderField: "Location"),
                       !location.isEmpty {
                        if let nextUrl = URL(string: location, relativeTo: url)?.absoluteString {
                            currentUrlString = nextUrl
                            continue
                        }
                    }
                    if !isKnown { break }
                } else {
                    break
                }
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
            if let match = queryItems.first(where: { $0.name.lowercased() == key })?.value {
                if match.hasPrefix("http://") || match.hasPrefix("https://") {
                    return unwrapParam(from: match)
                }
            }
        }
        return urlString
    }

    public func sendUrl(rawUrl: String) async throws -> (success: Bool, message: String) {
        let cleanBase = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)

        guard let endpointUrl = URL(string: "\(cleanBase)/api/extension/index-url") else {
            let msg = "Érvénytelen Base URL: \(cleanBase)"
            HistoryStore.shared.addLink(url: rawUrl, status: "Hiba", message: msg)
            return (false, msg)
        }

        let targetUrl = await resolveDestinationUrl(from: rawUrl)

        var request = URLRequest(url: endpointUrl)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(key, forHTTPHeaderField: "X-API-Key")
        request.setValue("NetLokator-iOS/1.0", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 15

        let payload: [String: String] = ["url": targetUrl]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            let msg = "Hálózati hiba: nincs válasz"
            HistoryStore.shared.addLink(url: targetUrl, status: "Hiba", message: msg)
            return (false, msg)
        }

        let responseBody = String(data: data, encoding: .utf8) ?? ""
        if (200...299).contains(httpResponse.statusCode) {
            let msg = "Sikeres beküldés (HTTP \(httpResponse.statusCode))"
            HistoryStore.shared.addLink(url: targetUrl, status: "Sikeres", message: msg)
            return (true, msg)
        } else {
            let msg = "Hiba (HTTP \(httpResponse.statusCode)): \(responseBody.prefix(120))"
            HistoryStore.shared.addLink(url: targetUrl, status: "Hiba", message: msg)
            return (false, msg)
        }
    }
}

private class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
