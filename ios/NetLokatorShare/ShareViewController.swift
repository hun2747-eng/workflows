import UIKit
import Social
import UniformTypeIdentifiers
import UserNotifications

class ShareViewController: UIViewController {
    private let card = UIView()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.2)
        setupUI()
        processSharedItem()
    }

    private func setupUI() {
        card.backgroundColor = UIColor.secondarySystemBackground
        card.layer.cornerRadius = 14
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.startAnimating()
        card.addSubview(spinner)

        label.text = "Küldés…"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 15, weight: .medium)
        label.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(label)

        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.85),
            spinner.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            spinner.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            label.topAnchor.constraint(equalTo: spinner.bottomAnchor, constant: 12),
            label.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            label.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            label.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -20),
        ])
    }

    private func processSharedItem() {
        guard let extensionItem = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = extensionItem.attachments else {
            showResult(ok: false, text: "Nincs megosztható tartalom")
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
                        self?.showResult(ok: false, text: "Nem sikerült beolvasni a linket")
                    }
                }
                return
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] item, _ in
                    if let text = item as? String {
                        let extracted = self?.extractHttpUrl(from: text) ?? text
                        self?.handleUrlString(extracted)
                    } else {
                        self?.showResult(ok: false, text: "Nem sikerült beolvasni a szöveget")
                    }
                }
                return
            }
        }
        showResult(ok: false, text: "Nincs támogatott tartalom")
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
                showResult(ok: res.success, text: res.message)
            } catch {
                showNotification(title: "NetLokátor – Hiba", body: error.localizedDescription)
                showResult(ok: false, text: error.localizedDescription)
            }
        }
    }

    private func showResult(ok: Bool, text: String) {
        DispatchQueue.main.async {
            self.spinner.stopAnimating()
            self.label.text = (ok ? "✓ " : "⚠️ ") + text
            DispatchQueue.main.asyncAfter(deadline: .now() + (ok ? 1.1 : 2.4)) {
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
