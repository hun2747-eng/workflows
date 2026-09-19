import UIKit
import Social
import UniformTypeIdentifiers
import UserNotifications

class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.2)
        processSharedItem()
    }
    
    private func processSharedItem() {
        guard let extensionItem = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = extensionItem.attachments else {
            finish()
            return
        }
        
        for provider in attachments {
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { [weak self] item, _ in
                    if let url = item as? URL {
                        self?.handleUrlString(url.absoluteString)
                    } else if let urlStr = item as? String {
                        self?.handleUrlString(urlStr)
                    } else {
                        self?.finish()
                    }
                }
                return
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] item, _ in
                    if let text = item as? String {
                        let extracted = self?.extractHttpUrl(from: text) ?? text
                        self?.handleUrlString(extracted)
                    } else {
                        self?.finish()
                    }
                }
                return
            }
        }
        finish()
    }
    
    private func extractHttpUrl(from text: String) -> String {
        let pattern = "https?://[^\\s]+"
        if let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
           let match = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count)),
           let range = Range(match.range, in: text) {
            return String(text[range])
        }
        return text
    }
    
    private func handleUrlString(_ rawUrl: String) {
        Task {
            do {
                let res = try await NetworkService.shared.sendUrl(rawUrl: rawUrl)
                showNotification(title: res.success ? "NetLokátor – Sikeres küldés" : "NetLokátor – Hiba", body: res.message)
            } catch {
                showNotification(title: "NetLokátor – Hiba", body: error.localizedDescription)
            }
            await MainActor.run {
                self.finish()
            }
        }
    }
    
    private func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
    
    private func finish() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}
