# Introduction and Inline Formatting

This sample document exercises every feature of the PDF engine. Paragraphs support **bold**, *italic*, ***bold italic***, `inline code`, and [external links](https://example.com/docs "title") plus bare URLs such as https://example.com/path/to/resource. Unicode is safe: arrows → ⇒ ← ⇔, “curly quotes”, ‘single’ quotes, en–dash, em—dash, ellipsis…, ≤ ≥ ≠, and the multiplication sign ×. A long unbreakable token like `com.example.messaging.service.NotificationService.submitMessageWithIdempotencyKey` must wrap instead of overflowing the margin, and so must this plain one: AVeryLongIdentifierWithoutAnySeparatorsThatKeepsGoingAndGoingAndGoingUntilItHitsTheRightMargin_and_beyond.

A second paragraph follows a blank line. It contains a hard line break right here  
so this text starts on a new line. See also [the architecture section](#architecture-diagrams) for an internal link.

## Lists

Bullets with deep nesting:

- Level 0 item one with **bold** text
  - Level 1 item with `code`
    - Level 2 item
      - Level 3 item that is long enough to wrap onto a second line so that the hanging indent is demonstrated clearly in the rendered output
      - Level 3 sibling
    - Level 2 sibling
  - Level 1 sibling
- Level 0 item two
- Level 0 item three with a long sentence that should wrap across more than one line to prove that wrapped lines align with the text and not with the bullet marker.

Numbered lists with nesting:

1. First step
   1. Sub-step one
   2. Sub-step two
      - Mixed bullet under a numbered sub-step
      - Another bullet
   3. Sub-step three
2. Second step
3. Third step
   - Notes about the third step
10. Tenth item to show marker alignment

Task list:

- [x] Parser implemented
- [x] Renderer implemented
- [ ] Content authored by the lead

### Level 3 heading

Body text under an h3 heading. Headings of level 4 follow.

#### Level 4 heading

Short paragraph under h4.

## Quotes, Rules and Callouts

> Design is not just what it looks like and feels like. Design is how it works.
> A blockquote can span several lines and contains *inline* formatting.

---

> [!NOTE]
> This is a note callout. It supports **inline formatting**, `code`, and lists:
> - first point
> - second point

> [!WARNING]
> Warning callouts highlight risky behaviour, e.g. provider acceptance never implies delivery.

> [!TIP] Custom title
> Tips can carry a custom title.

:::important Container syntax
The `:::` container syntax works too, with multiple paragraphs.

Second paragraph inside the container, with a [link](https://example.com).
:::

## Figure Placeholder

![Figure caption for a missing image to exercise the placeholder](does-not-exist.png)
