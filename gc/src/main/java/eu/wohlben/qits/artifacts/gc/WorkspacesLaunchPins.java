package eu.wohlben.qits.artifacts.gc;

/**
 * The images a workspace or editor start would pull today — qits-workspaces answering for itself.
 *
 * <p>{@code GET /workspaces/api/pins}, read out of that service's own resolved {@code
 * qits.workspace.image-repo}/{@code -version} and {@code qits.editor.image-repo}/{@code -version}.
 * It is the first pin source that is a fact about a <b>running process</b> rather than about a
 * stored row: the container qits-workspaces would start in the next second, at the configuration it
 * is holding in the next second.
 *
 * <p>{@link LaunchImagePins} carries why this is the only source for these images.
 */
@FunctionalInterface
public interface WorkspacesLaunchPins extends LaunchImagePins {}
